# Smart Eyes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pixel 8a app + FastAPI backend that make Ray-Ban Meta glasses warn unprompted about head-height obstacles, describe/read on button press, and answer "where are my keys?" from logged observations.

**Architecture:** Android app is a thin hub: grabs a frame (glasses or phone camera), posts it to the backend, plays the returned MP3 through the glasses (BT audio sink). Backend on Vultr does all AI: Gemini vision → JSON, ElevenLabs TTS/STT, logs to Tiger Data, serves a polling dashboard. Cane firmware is the teammate's; the app only consumes its BLE events.

**Tech Stack:** Python 3.12, FastAPI, `google-genai`, `elevenlabs`, `psycopg[binary]` 3, pytest · Kotlin, Android Studio, minSdk 29, OkHttp, CameraX, kotlinx-coroutines, Meta Wearables Device Access Toolkit (DAT) · Caddy, systemd on Vultr Ubuntu.

**Spec:** `docs/superpowers/specs/2026-11-07-smart-eyes-design.md`

## Global Constraints

- API keys live only on the server (env vars). APK holds only backend URL + shared secret.
- Every backend request needs header `X-Secret: <SHARED_SECRET>` (dashboard/audio may pass `?key=<SHARED_SECRET>` instead). Wrong/missing → 401.
- Frames: JPEG, long edge ~512 px.
- Hazard = **head height only** (chest-to-head level, in walking path). Chairs/ground obstacles are never hazards. Hazard `say` < 6 words.
- Hazard cadence default 4 s (config). Never queue: skip tick if any request in flight; button pre-empts a pending hazard request.
- Same hazard not repeated within 10 s.
- Hazard mode with no hazard → no audio at all.
- ElevenLabs TTS model `eleven_flash_v2_5`; STT model `scribe_v1`.
- Pre-generated hazard clips: id `hazard_stop` = "Stop, head height", id `hazard_duck` = "Duck, head height".
- Offline phrase (Android TTS): "Connection lost, the cane is still active." — at most once per 30 s.
- Memory fallback question when STT is empty: "where are my keys?"
- BLE: name `SmartCane`, service `709dd8c2-e2ae-492e-bcbc-ab2ad425d950`, characteristic `51cfb510-0815-4e68-aabc-256bbb5a64fb` (Read+Notify, 1 byte: `1` PRESS, `2` HOLD, `3` DOUBLE).
- Phone uses mobile data, not venue Wi-Fi. Log real latency.
- Cut line: Tasks 1–6 must work on real glasses before Tasks 7+ start.

## Review Focus

1. Gemini fails (429 quota, timeout, malformed JSON) → describe/read_text still speak "Sorry, I couldn't see that."; hazard stays silent; server never 500s. (Task 2 test `test_frame_gemini_error_*`)
2. ElevenLabs fails → response still has `say` with `audio_id: null`; phone speaks `say` with Android TTS. (Task 2 test `test_frame_tts_failure_returns_say`, Task 3 Player rule)
3. Hazard returns same warning every tick while walker stands still → only first one spoken within 10 s. (Task 4 `test_hazard_cooldown`)
4. Button pressed while a hazard request is in flight → hazard result discarded, button request runs immediately. (Task 6 `GateTest`)
5. Backend unreachable for minutes → offline phrase not spammed every 4 s. (Task 6 `ThrottleTest`)

---

## File Structure

```
backend/
  app/main.py        routes, auth, wiring
  app/gemini.py      analyze(jpeg, mode) -> Vision, answer(question, rows) -> str, key rotation
  app/tts.py         speak/get/preload_hazard_clips/transcribe, in-memory audio store
  app/cooldown.py    Cooldown
  app/db.py          init/log_event/recent_events/recent_observations (Tiger Data + in-memory deque)
  app/static/index.html  dashboard
  tests/test_api.py, tests/test_cooldown.py
  requirements.txt, .env.example
  deploy/Caddyfile, deploy/smart-eyes.service
android/             Android Studio project, package com.smarteyes
  app/src/main/java/com/smarteyes/
    MainActivity.kt      status text + debug buttons PRESS/HOLD/DOUBLE + frame-source toggle
    Backend.kt           OkHttp client
    FrameSource.kt       interface + PhoneFrameSource (CameraX) + GlassesFrameSource (DAT)
    Player.kt            play audio_id URL, else Android TTS of say
    Gate.kt              Gate, Throttle (pure Kotlin)
    Hub.kt               hazard loop + button handling (uses Gate)
    CaneBle.kt           BLE client
    VoiceRecorder.kt     4 s record via SCO, phone-mic fallback
  app/src/test/java/com/smarteyes/GateTest.kt, ThrottleTest.kt
```

---

### Task 1: Provision accounts + hand off BLE contract (manual)

**Files:** Create `backend/.env.example`

- [ ] **Step 1:** Register GoDaddy domain now (DNS lag). Create Vultr Ubuntu 24.04 VM (smallest plan). Add A record domain → VM IP.
- [ ] **Step 2:** Gemini keys from aistudio.google.com (yours + 2 teammates). In AI Studio check free-tier RPM; pick a vision model with ≥15 RPM free for `GEMINI_MODEL`.
- [ ] **Step 3:** ElevenLabs API key; pick a voice, note its voice id. Tiger Data: create service, copy connection string.
- [ ] **Step 4:** Send teammate the BLE contract from Global Constraints. Get an ETA.
- [ ] **Step 5:** Write `backend/.env.example` with keys (no values): `SHARED_SECRET, GEMINI_API_KEYS (comma-separated), GEMINI_MODEL, ELEVEN_API_KEY, ELEVEN_VOICE_ID, DATABASE_URL, HAZARD_COOLDOWN_S=10`. Real `.env` is gitignored.
- [ ] **Step 6:** Commit `.env.example` + `.gitignore` (`.env`, `__pycache__/`, `android/.gradle/`, `android/build/`, `android/app/build/`, `local.properties`).

Done when: domain resolves to VM IP (`nslookup <domain>`), all keys in a local `backend/.env`.

---

### Task 2: Backend core — `/frame` describe + read_text, `/audio`

**Files:** Create `backend/app/{main,gemini,tts,db}.py`, `backend/requirements.txt`, `backend/tests/test_api.py`

**Interfaces:**
- Produces:
  - `gemini.Vision(BaseModel)`: `hazard: bool, say: str, objects: list[str], caption: str`
  - `gemini.analyze(jpeg: bytes, mode: str) -> Vision` — raises `gemini.GeminiError` on any failure
  - `tts.speak(text: str) -> str | None` (audio_id; None on failure), `tts.get(audio_id: str) -> bytes | None`
  - `db.log_event(ev: dict) -> None` (this task: append to `RECENT: deque(maxlen=200)` only)
  - `POST /frame` multipart: `image` (file), `mode` ∈ `describe|read_text|hazard`, optional `client_ms: int` (phone-measured latency of its previous request). Returns `{"say": str, "hazard": bool, "audio_id": str|null, "server_ms": int}`.
  - `GET /audio/{id}` → `audio/mpeg`, 404 if unknown.

- [ ] **Step 1: Write failing tests** in `backend/tests/test_api.py` using `TestClient`, monkeypatching `app.gemini.analyze` and `app.tts.speak`:

```python
H = {"X-Secret": "test"}  # conftest/env sets SHARED_SECRET=test
JPG = b"\xff\xd8fake"

def test_frame_requires_secret(client):
    assert client.post("/frame", files={"image": ("f.jpg", JPG)}, data={"mode": "describe"}).status_code == 401

def test_frame_bad_mode(client):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "dance"})
    assert r.status_code == 422

def test_frame_empty_image(client):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", b"")}, data={"mode": "describe"})
    assert r.status_code == 400

def test_frame_describe(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A red backpack on a table.", objects=["backpack on table"], caption="x"))
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "describe"}).json()
    assert r["say"] == "A red backpack on a table." and r["hazard"] is False and r["audio_id"] == "a1"
    assert client.get("/audio/a1", headers=H).status_code == 200  # fake_speak stores bytes under "a1"

def test_frame_gemini_error_describe_apologizes(client, gemini_raises, fake_speak):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "describe"}).json()
    assert r["say"] == "Sorry, I couldn't see that."

def test_frame_gemini_error_hazard_silent(client, gemini_raises):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "hazard"}).json()
    assert r == {**r, "say": "", "hazard": False, "audio_id": None}

def test_frame_tts_failure_returns_say(client, fake_vision, speak_fails):
    fake_vision(Vision(hazard=False, say="A door.", objects=[], caption=""))
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "describe"}).json()
    assert r["say"] == "A door." and r["audio_id"] is None

def test_frame_logs_event(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A door.", objects=["door on right"], caption="hall"))
    client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "describe", "client_ms": "2400"})
    ev = db.RECENT[-1]
    assert ev["mode"] == "describe" and ev["objects"] == ["door on right"] and ev["latency_ms"] == 2400
```

Fixtures (`client`, `fake_vision`, `gemini_raises`, `fake_speak`, `speak_fails`) go in `backend/tests/conftest.py`; `fake_speak` stores `b"mp3"` in the tts store under `"a1"` and returns `"a1"`. `client` fixture sets `SHARED_SECRET=test`, unsets `DATABASE_URL`, clears `db.RECENT` and (from Task 4) resets the hazard `Cooldown`. Add helper `post_frame(client, mode) -> dict` there too.

- [ ] **Step 2:** `cd backend && pytest -q` → FAIL (import errors).
- [ ] **Step 3: Implement.**
  - `requirements.txt`: `fastapi uvicorn[standard] python-multipart google-genai elevenlabs psycopg[binary] python-dotenv pytest httpx`.
  - `gemini.analyze`: `google.genai.Client(api_key=...)`, `models.generate_content(model=GEMINI_MODEL, contents=[types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"), PROMPTS[mode]], config=types.GenerateContentConfig(response_mime_type="application/json", response_schema=Vision))`, parse `resp.parsed`. Rotate to next key in `GEMINI_API_KEYS` on 429, try each key once, then raise `GeminiError`. Timeout ~8 s.
  - `PROMPTS`: every mode asks for `objects` as short "item on/near place" strings for memory. describe → `say` = one short sentence. read_text → `say` = visible text read out (or "I don't see any text."). hazard → see Task 4 (stub: same schema).
  - `tts.speak`: `ElevenLabs(api_key).text_to_speech.convert(voice_id, text=text, model_id="eleven_flash_v2_5", output_format="mp3_44100_128")`, join bytes, store under `uuid4().hex[:8]` in an `OrderedDict` capped at 100. Any exception → `None`.
  - `main.py`: auth dependency checks `X-Secret` header or `key` query param. `/frame` runs `analyze` in threadpool (`run_in_threadpool`), builds response, `background_tasks.add_task(db.log_event, {...})` with keys `ts, mode, say, objects, caption, hazard, latency_ms` (`latency_ms` = `client_ms` if given else `server_ms`).
- [ ] **Step 4:** `pytest -q` → all pass.
- [ ] **Step 5: Smoke with real keys:** `uvicorn app.main:app --host 0.0.0.0 --port 8000`, then `curl -H "X-Secret: $S" -F image=@test.jpg -F mode=describe localhost:8000/frame` → sensible sentence; `curl .../audio/<id> -o a.mp3` plays.
- [ ] **Step 6: Commit** `feat(backend): /frame describe+read_text with Gemini and ElevenLabs`.

---

### Task 3: Android core pipe with phone camera

**Files:** Create Android project in `android/` (Empty Views Activity, package `com.smarteyes`, minSdk 29), `MainActivity.kt`, `Backend.kt`, `FrameSource.kt` (interface + `PhoneFrameSource`), `Player.kt`

**Interfaces:**
- Consumes: `/frame`, `/audio/{id}` (Task 2).
- Produces:
  - `interface FrameSource { suspend fun latestJpeg(): ByteArray? ; fun start(); fun stop() }`
  - `data class Reply(val say: String, val hazard: Boolean, val audioId: String?, val serverMs: Int)`
  - `class Backend(baseUrl: String, secret: String) { suspend fun frame(jpeg: ByteArray, mode: String, clientMs: Int?): Reply; suspend fun ask(audio: ByteArray, filename: String, clientMs: Int?): Reply; fun audioUrl(id: String): String }` — throws `IOException` when unreachable. OkHttp timeouts: connect 3 s, read 10 s.
  - `class Player(ctx) { fun play(reply: Reply, backend: Backend); fun speakLocal(text: String) }` — `audioId != null` → `MediaPlayer` with `AudioAttributes.USAGE_MEDIA`, `setDataSource(ctx, uri, mapOf("X-Secret" to secret))`; else if `say` non-empty → Android `TextToSpeech`.
- Config: `BuildConfig.BACKEND_URL`, `BuildConfig.SHARED_SECRET` from `local.properties` (gitignored). Debug manifest allows cleartext for the dev IP.

- [ ] **Step 1:** Create project; add OkHttp, CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`), coroutines. CAMERA + INTERNET + VIBRATE permissions.
- [ ] **Step 2:** `PhoneFrameSource`: CameraX `ImageCapture` (or `ImageAnalysis` keeping last frame) → scale long edge to 512 → JPEG quality 80.
- [ ] **Step 3:** `MainActivity`: status `TextView` (last say + latency), buttons PRESS / HOLD / DOUBLE (DOUBLE disabled until Task 9). PRESS → vibrate 50 ms, `frame(jpeg,"describe")`, `Player.play`. HOLD → `"read_text"`. Measure press→playback-start ms; send as `clientMs` on the next request. All buttons have `contentDescription` (TalkBack).
- [ ] **Step 4: Verify on Pixel** (backend on laptop, phone on same network for now): PRESS → spoken description within ~3 s; status shows latency. Kill backend → PRESS → Android TTS "Connection lost, the cane is still active."
- [ ] **Step 5: Commit** `feat(android): core describe/read pipe with phone camera`.

---

### Task 4: Backend hazard mode + cached clips + cooldown

**Files:** Create `backend/app/cooldown.py`, `backend/tests/test_cooldown.py`; modify `gemini.py` (hazard prompt), `tts.py` (`preload_hazard_clips`), `main.py`, `tests/test_api.py`

**Interfaces:**
- Produces: `Cooldown(seconds: float)`, `.allow(key: str, now: float) -> bool`; `tts.preload_hazard_clips() -> None` stores ids `hazard_stop`, `hazard_duck` (called on FastAPI startup; failure logged, not fatal).

- [ ] **Step 1: Failing tests**

```python
# test_cooldown.py
def test_cooldown():
    c = Cooldown(10)
    assert c.allow("stop", 0) and not c.allow("stop", 9.9) and c.allow("stop", 10.1)
    assert c.allow("duck", 5)  # different key independent

# test_api.py
def test_hazard_none_is_silent(client, fake_vision):
    fake_vision(Vision(hazard=False, say="Chair ahead", objects=[], caption=""))
    r = post_frame(client, "hazard")
    assert r["audio_id"] is None and r["say"] == "" and r["hazard"] is False

def test_hazard_uses_cached_clip(client, fake_vision):
    fake_vision(Vision(hazard=True, say="Duck, branch ahead", objects=[], caption=""))
    assert post_frame(client, "hazard")["audio_id"] == "hazard_duck"

def test_hazard_cooldown(client, fake_vision):
    fake_vision(Vision(hazard=True, say="Stop, head height", objects=[], caption=""))
    assert post_frame(client, "hazard")["audio_id"] == "hazard_stop"
    assert post_frame(client, "hazard")["audio_id"] is None  # within 10 s
```

- [ ] **Step 2:** `pytest -q` → new tests FAIL.
- [ ] **Step 3: Implement.** Clip choice: `"duck"` in `say.lower()` → `hazard_duck`, else `hazard_stop`. Cooldown key = clip id. Hazard results are still logged (with `hazard`). Hazard prompt (exact intent): *"You are a mobility aid for a blind person walking forward. hazard=true ONLY if an object is at the person's head or chest height in their walking path within about 2 meters (e.g. branch, sign, open cabinet door, hanging object). Floor and low obstacles such as chairs, tables, steps and bags are NOT hazards. If hazard, say is under 6 words and includes 'duck' when they can pass under it. Otherwise say is empty. Always list visible objects as 'item on/near place'."*
- [ ] **Step 4:** `pytest -q` → pass.
- [ ] **Step 5: Commit** `feat(backend): head-height hazard mode with cached clips and cooldown`.

---

### Task 5: Glasses frame source (Meta DAT)

**Files:** Create `GlassesFrameSource` in `FrameSource.kt`; modify `MainActivity.kt` (toggle Phone/Glasses, default Glasses), Gradle (DAT dependency)

**Interfaces:** Consumes `FrameSource` (Task 3).

- [ ] **Step 1:** Phone setup per spec checklist: Meta AI app, glasses paired + firmware updated, Developer Mode on, check DAT Version Dependencies page.
- [ ] **Step 2:** Build and run Meta's DAT Android sample unchanged; confirm live video. **Go/no-go: if no video after ~1 h, stop, keep `PhoneFrameSource` as default, move on to Task 6.**
- [ ] **Step 3:** Port the sample's registration + stream-session code into `GlassesFrameSource`: start one session with a fixed resolution/fps (never change mid-session — toolkit bug), keep the latest frame in an `AtomicReference`, `latestJpeg()` encodes it at 512 px.
- [ ] **Step 4: Verify** with glasses on: PRESS → description of what the *glasses* see. With the stream running, confirm the reply audio comes out of the glasses speaker (spec Day-1 check). If not, note it and use phone speaker / wired earbuds.
- [ ] **Step 5: Commit** `feat(android): glasses camera frame source via DAT`.

---

### Task 6: Hazard loop on phone (the demo moment)

**Files:** Create `Gate.kt`, `Hub.kt`, `app/src/test/.../GateTest.kt`, `ThrottleTest.kt`; modify `MainActivity.kt` (start/stop hazard switch; buttons route through `Hub`)

**Interfaces:**
- Produces:
  - `enum class Decision { START, CANCEL_AND_START, IGNORE }`
  - `class Gate { fun hazardTick(): Decision; fun button(): Decision; fun done() }` — states idle / hazard-in-flight / button-in-flight.
  - `class Throttle(periodMs: Long) { fun allow(nowMs: Long): Boolean }`
  - `class Hub(scope, source: FrameSource, backend: Backend, player: Player, cadenceMs: Long = 4000) { fun startHazards(); fun stopHazards(); fun onButton(event: Int) }` — `event` uses BLE values 1/2/3.

- [ ] **Step 1: Failing tests**

```kotlin
class GateTest {
  @Test fun idleHazardStarts() = assertEquals(Decision.START, Gate().hazardTick())
  @Test fun hazardSkippedWhileInFlight() { val g = Gate(); g.hazardTick(); assertEquals(Decision.IGNORE, g.hazardTick()) }
  @Test fun buttonPreemptsHazard() { val g = Gate(); g.hazardTick(); assertEquals(Decision.CANCEL_AND_START, g.button()) }
  @Test fun hazardSkippedWhileButton() { val g = Gate(); g.button(); assertEquals(Decision.IGNORE, g.hazardTick()) }
  @Test fun secondButtonIgnored() { val g = Gate(); g.button(); assertEquals(Decision.IGNORE, g.button()) }
  @Test fun doneFrees() { val g = Gate(); g.button(); g.done(); assertEquals(Decision.START, g.hazardTick()) }
}
class ThrottleTest {
  @Test fun throttles() { val t = Throttle(30_000); assertTrue(t.allow(0)); assertFalse(t.allow(29_999)); assertTrue(t.allow(30_000)) }
}
```

- [ ] **Step 2:** `./gradlew test` → FAIL.
- [ ] **Step 3: Implement** `Gate`, `Throttle`; `Hub` keeps the in-flight `Job`, on `CANCEL_AND_START` cancels it (its result is dropped) and launches the button request; calls `gate.done()` in `finally`. Hazard loop: `while(active) { if (gate.hazardTick()==START) launch request; delay(cadenceMs) }`. Only play when `reply.audioId != null` for hazards. `IOException` → `player.speakLocal(offline phrase)` only if `Throttle(30_000).allow(now)`.
- [ ] **Step 4:** `./gradlew test` → pass.
- [ ] **Step 5: Verify the moment:** hang a large high-contrast object at head height. Walk slowly toward it with glasses on, hazard switch on, nobody pressing anything → hears "Stop/Duck, head height" before reaching it. Walk toward a chair → glasses stay silent. Standing still under it → no repeat within 10 s.
- [ ] **Step 6: Commit** `feat(android): unprompted head-height hazard loop`.

**CUT LINE — everything above must work on real glasses before continuing.**

---

### Task 7: Deploy backend to Vultr with HTTPS

**Files:** Create `backend/deploy/Caddyfile`, `backend/deploy/smart-eyes.service`

- [ ] **Step 1:** On VM: `apt install python3-venv caddy`, clone repo to `/opt/smart-eyes`, venv, `pip install -r backend/requirements.txt`, copy `.env`.
- [ ] **Step 2:** `smart-eyes.service`: `WorkingDirectory=/opt/smart-eyes/backend`, `EnvironmentFile=/opt/smart-eyes/backend/.env`, `ExecStart=.../venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000`, `Restart=always`. Enable + start.
- [ ] **Step 3:** `Caddyfile`: `<domain> { reverse_proxy 127.0.0.1:8000 }`. Reload Caddy. Open ports 80/443 in Vultr firewall.
- [ ] **Step 4: Verify:** `curl -H "X-Secret: $S" -F image=@test.jpg -F mode=describe https://<domain>/frame` → JSON. Switch app `BACKEND_URL` to `https://<domain>`, phone on mobile data, PRESS works.
- [ ] **Step 5: Commit** `chore: Vultr deploy (systemd + Caddy)`.

---

### Task 8: Cane BLE client

**Files:** Create `CaneBle.kt`; modify `MainActivity.kt` (BLE permissions, status "Cane: connected/searching")

**Interfaces:** Consumes `Hub.onButton(event: Int)` (Task 6), BLE contract (Global Constraints).

- [ ] **Step 1:** `CaneBle(ctx, onEvent: (Int) -> Unit)`: scan filtered by service UUID, connect, enable notifications on the characteristic (write CCCD `0x2902`), each notify → `onEvent(value[0].toInt())`, ignore values not in 1..3. On disconnect → rescan after 2 s (auto-reconnect). Permissions `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`.
- [ ] **Step 2: Verify** with teammate's firmware (or nRF Connect on another phone emulating the service): press → description, hold → read text, glasses stream + cane connected at the same time (spec Day-1 check). Turn cane off/on → reconnects without touching the app.
- [ ] **Step 3: Commit** `feat(android): cane BLE button events`.

---

### Task 9: Tiger Data log, `/events`, dashboard

**Files:** Modify `backend/app/db.py`, `main.py`; create `backend/app/static/index.html`; tests in `test_api.py`

**Interfaces:**
- Produces: `db.init() -> None`, `db.recent_events(limit: int = 100) -> list[dict]` (newest first), `db.recent_observations(minutes: int = 60, limit: int = 40) -> list[dict]` (rows with non-empty `objects`, newest first; DB if available else deque). `GET /events` → `recent_events()`. `GET /` → dashboard.

- [ ] **Step 1: Failing tests:** `test_events_newest_first` (two `/frame` posts → `/events[0]` is the second), `test_events_requires_secret`, `test_log_without_db_does_not_fail` (`DATABASE_URL` unset → `/frame` still 200 and event in `/events`).
- [ ] **Step 2:** `pytest -q` → FAIL.
- [ ] **Step 3: Implement.** `init()` on startup if `DATABASE_URL`: `CREATE TABLE IF NOT EXISTS events (ts timestamptz NOT NULL, mode text, say text, objects text[], caption text, hazard bool, latency_ms int)` then `SELECT create_hypertable('events', by_range('ts'), if_not_exists => TRUE)`. Any DB error → log, set `DB_OK=False`, keep going (deque only). `log_event` inserts when `DB_OK`. Dashboard: one HTML file, `fetch('/events?key=' + key)` every 2 s (key from page URL `?key=`); table of ts / mode / say / latency_ms; hazard rows highlighted; header shows median latency per mode. Large readable type.
- [ ] **Step 4:** `pytest -q` → pass. Open `https://<domain>/?key=<secret>` while pressing buttons → rows appear within 2 s; rows visible in Tiger Data console.
- [ ] **Step 5: Commit** `feat(backend): Tiger Data logging and live dashboard`.

---

### Task 10: Memory — `/ask` backend

**Files:** Modify `gemini.py` (`answer`), `tts.py` (`transcribe`), `main.py`; tests in `test_api.py`

**Interfaces:**
- Produces: `tts.transcribe(audio: bytes, filename: str) -> str` (`""` on failure; `ElevenLabs.speech_to_text.convert(file=..., model_id="scribe_v1").text`), `gemini.answer(question: str, rows: list[dict]) -> str`, `POST /ask` multipart `audio` file + optional `client_ms` → same `Reply` shape, logged with `mode="ask"`.

- [ ] **Step 1: Failing tests:**

```python
def test_ask_empty_stt_defaults_to_keys(client, monkeypatch, fake_speak):
    monkeypatch.setattr(tts, "transcribe", lambda a, f: "")
    seen = {}
    monkeypatch.setattr(gemini, "answer", lambda q, rows: seen.setdefault("q", q) or "x")
    client.post("/ask", headers=H, files={"audio": ("a.m4a", b"x")})
    assert seen["q"] == "where are my keys?"

def test_ask_no_observations(client, monkeypatch, fake_speak):
    monkeypatch.setattr(tts, "transcribe", lambda a, f: "where are my keys?")
    r = client.post("/ask", headers=H, files={"audio": ("a.m4a", b"x")}).json()
    assert r["say"] == "I haven't seen anything yet."
```

- [ ] **Step 2:** `pytest -q` → FAIL.
- [ ] **Step 3: Implement.** `/ask`: transcribe → default question if empty → `rows = db.recent_observations()` → none → "I haven't seen anything yet." → else `gemini.answer` with rows as lines `"<minutes> min ago: <objects joined>"`, prompt: answer in one sentence with place and how long ago, or say you haven't seen it. Gemini error → "Sorry, I couldn't remember that." → TTS.
- [ ] **Step 4:** `pytest -q` → pass.
- [ ] **Step 5: Commit** `feat(backend): /ask memory from logged observations`.

---

### Task 11: Voice question on phone (DOUBLE)

**Files:** Create `VoiceRecorder.kt`; modify `Hub.kt` (event 3), `MainActivity.kt` (enable DOUBLE, `RECORD_AUDIO` permission)

**Interfaces:** Consumes `Backend.ask` (Task 3), `/ask` (Task 10).

- [ ] **Step 1:** `VoiceRecorder.record4s(): ByteArray` — `AudioManager` SCO on (`setCommunicationDevice` to the glasses BT SCO device on API 31+), `MediaRecorder` AAC/M4A 4 s, then **SCO off before returning** (so the answer plays as media). If no SCO device within 1 s → record from phone mic.
- [ ] **Step 2:** `Hub.onButton(3)`: vibrate, short beep/“Listening” via local TTS, record, `backend.ask`, play. Goes through `Gate.button()` like other buttons.
- [ ] **Step 3: Verify:** look at keys on a table, PRESS once (describe). Walk away, DOUBLE, say "where are my keys?" → answer names the table and minutes ago, through glasses speaker. Repeat with mic silent → still answers the default question.
- [ ] **Step 4: Commit** `feat(android): voice memory question via glasses mic`.

---

### Task 12: Demo hardening

- [ ] **Step 1:** Set up exact stage props (large high-contrast hanging object, chair). Capture 20–30 glasses frames of the approach in stage lighting; run them through `/frame mode=hazard` with a small script (`backend/scripts/eval_hazard.py <dir>` printing hazard/say per file). Tune the hazard prompt until hanging-object frames → hazard and chair-only frames → no hazard.
- [ ] **Step 2:** Read median latencies off the dashboard per mode; write them into the pitch notes.
- [ ] **Step 3:** Rehearse full demo script 3× on mobile data; record backup video.
- [ ] **Step 4: Commit** `chore: hazard prompt tuning + eval script`.

---

## Not in this plan

- R4 firmware (teammate, against BLE contract).
- On-phone YOLO, Sky Mode, SOS/fall detection (spec non-goals / stretch).
