# On-phone YOLO Hazard Warnings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** YOLO11n runs on the Pixel all the time and warns offline about head-height umbrellas/backpacks; Gemini stays the second opinion; every hazard event (spoken/muted/cooldown) is logged and shown as a live YOLO-vs-Gemini timeline; a phone debug view shows boxes for on-site tuning.

**Architecture:** Pure-Kotlin decision logic (decode → head-height rule → warn-once tracker → shared quiet period) unit-tested on the JVM; a thin TFLite runner feeds it frames every 400 ms. The phone decides each event's status and posts it to a new backend endpoint; backend stores it in a Tiger Data hypertable and serves it to the dashboard timeline.

**Tech Stack:** Kotlin, LiteRT/TFLite (`org.tensorflow:tensorflow-lite`), Ultralytics YOLO11n exported to TFLite fp16 @320 · FastAPI, psycopg, TimescaleDB.

**Spec:** `docs/superpowers/specs/2026-11-07-yolo-offline-design.md` (Revision 2 wins over earlier lines)

## Global Constraints

- Constants at the top of ONE file (`HazardConfig.kt`): `TOP_ZONE = 0.40f` (box centre y ≤ this, normalised, 0 = top), `MIN_AREA = 0.05f` (box w×h, normalised), `MIN_CONF = 0.40f`, `HAZARD_CLASSES = setOf("umbrella", "backpack")`, `IGNORED_CLASSES = setOf("person")`, `PERSIST_FRAMES = 2`, `REARM_FRAMES = 4`, `QUIET_MS = 5_000L`, `YOLO_PERIOD_MS = 400L`.
- Statuses exactly `spoken | muted | cooldown`; sources exactly `yolo | gemini`.
- YOLO speech: cached `hazard_stop` clip (downloaded at app start from `GET /audio/hazard_stop`), fallback Android TTS "Careful, head height."
- While a voice question records: YOLO hazards → `muted`, not spoken.
- YOLO inference never on the main thread; skip a tick if the previous inference is still running.
- Backend API unchanged except the two new endpoints. Hazard event posting is fire-and-forget; failures are swallowed.

## Review Focus

1. Umbrella lying on the floor (box centre low in frame) → must NOT warn. (Task 3 `floorUmbrellaIgnored`)
2. Standing still in front of the umbrella → warns exactly once, not every 5 s. (Task 3 `warnsOnceWhileObstacleStays`)
3. Gemini confirms the same umbrella 2 s after YOLO spoke → `muted`, silent, logged. (Task 3 `geminiInsideQuietPeriodMuted`)
4. Backend down → hazard event post fails silently; YOLO still speaks. (Task 5 Hub code + Task 1 swallow)
5. Model output layout different from assumed `[1, 84, N]` → decode test on a synthetic tensor + on-device check catch it. (Task 4)

---

### Task 1: Backend hazard events (table, POST /hazard_event, GET /hazards)

**Files:** Modify `backend/app/db.py`, `backend/app/main.py`; Test `backend/tests/test_hazards.py`

**Interfaces — Produces:**
- `POST /hazard_event` JSON `{"source": "yolo"|"gemini", "label": str, "confidence": float|null, "latency_ms": int, "status": "spoken"|"muted"|"cooldown"}` → `{"ok": true}`; 422 on bad source/status. Server stamps `ts`.
- `GET /hazards` → list (newest first, ≤100) of `{ts, source, label, confidence, latency_ms, status}`.
- `db.log_hazard(ev: dict)`, `db.recent_hazards(limit=100)`; table `hazard_events (ts timestamptz NOT NULL, source text, label text, confidence real, latency_ms int, status text)` hypertable on `ts`, created in `db.init()`.

- [ ] **Step 1: Failing tests** — `test_hazard_event_logged_and_listed` (post yolo spoken then gemini muted → `/hazards` returns gemini first, fields round-trip, confidence null kept), `test_hazard_event_bad_status_422`, `test_hazard_event_requires_secret`.
- [ ] **Step 2:** `pytest -q` → FAIL.
- [ ] **Step 3:** Implement with a `HazardIn` pydantic model using `Literal` for source/status; in-memory `HAZARDS: deque(maxlen=300)` + DB insert when `DB_OK` (same pattern as `log_event`).
- [ ] **Step 4:** `pytest -q` → all pass. Against real Tiger Data: post one event, read it back from `hazard_events`, delete it.
- [ ] **Step 5: Commit** `feat(backend): hazard event log endpoint + Tiger Data table`.

### Task 2: Dashboard hazard timeline

**Files:** Modify `backend/app/static/index.html`; Test `backend/tests/test_api.py::test_dashboard_has_timeline`

- [ ] **Step 1: Failing test** — dashboard HTML contains `id="timeline"` and fetches `/hazards`.
- [ ] **Step 2:** Implement: new top section "Hazard timeline" polling `/hazards` every 1 s. Group into bursts (gap > 10 s starts new burst), oldest-first within the latest burst, each row: offset from burst start (`0.1 s`), source badge (YOLO = solid amber, Gemini = solid blue, big text), label, latency, status pill (`SPOKEN` green, `MUTED (quiet period)` grey, `COOLDOWN` grey). Show the last 3 bursts.
- [ ] **Step 3:** `pytest -q` → pass; open `/?key=change-me`, post 2 sample events with curl, rows appear within 1 s.
- [ ] **Step 4: Commit** `feat(dashboard): YOLO vs Gemini hazard timeline`.

### Task 3: Hazard decision logic (pure Kotlin)

**Files:** Create `android/app/src/main/java/com/smarteyes/HazardConfig.kt`, `HazardLogic.kt`; Test `android/app/src/test/java/com/smarteyes/HazardLogicTest.kt`

**Interfaces — Produces:**
- `data class Det(val label: String, val conf: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)` (normalised 0..1, y down)
- `fun headHazard(dets: List<Det>): Det?` — best (highest conf) det passing: label in `HAZARD_CLASSES`, not in `IGNORED_CLASSES`, `conf ≥ MIN_CONF`, `cy ≤ TOP_ZONE`, `w*h ≥ MIN_AREA`.
- `class YoloTracker { fun update(hit: Det?): Det? }` — returns the Det on the frame a warning should fire: after `PERSIST_FRAMES` consecutive hits while armed; then disarmed until `REARM_FRAMES` consecutive misses.
- `enum class HazardStatus { SPOKEN, MUTED, COOLDOWN }` with `val wire = name.lowercase()`
- `class QuietPeriod(private val ms: Long = QUIET_MS) { fun decide(nowMs: Long, recording: Boolean, serverCooldown: Boolean = false): HazardStatus }` — `serverCooldown` → COOLDOWN; `recording` or inside window → MUTED; else SPOKEN and the window starts at `nowMs`.

- [ ] **Step 1: Failing tests** (names → assertions):
  - `hungUmbrellaTriggers`: `Det("umbrella",.8f,.5f,.25f,.4f,.3f)` → returned.
  - `floorUmbrellaIgnored`: same with `cy=.8f` → null.
  - `smallFarUmbrellaIgnored`: `w=.1f,h=.1f` → null.
  - `personIgnored`: label `person` at head height → null; `chairIgnored`: label `chair` → null.
  - `lowConfidenceIgnored`: conf `.2f` → null.
  - `warnsOnceWhileObstacleStays`: hit ×10 → exactly one non-null (on 2nd frame).
  - `rearmsAfterObstacleGone`: hit ×3, miss ×4, hit ×2 → two warnings total.
  - `flickerDoesNotRearm`: hit ×3, miss ×2, hit ×2 → one warning.
  - `geminiInsideQuietPeriodMuted`: `decide(0,false)`=SPOKEN, `decide(2000,false)`=MUTED, `decide(5001,false)`=SPOKEN.
  - `recordingMutes`: `decide(0, recording=true)`=MUTED and does not start the window (`decide(100,false)`=SPOKEN).
  - `serverCooldownReported`: `decide(0,false,serverCooldown=true)`=COOLDOWN.
- [ ] **Step 2:** `./gradlew testDebugUnitTest` → FAIL (unresolved).
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** tests pass.
- [ ] **Step 5: Commit** `feat(android): head-height hazard rule, warn-once tracker, quiet period`.

### Task 4: YOLO model + TFLite runner

**Files:** Create `tools/export_yolo.py`, `android/app/src/main/assets/yolo11n_320_fp16.tflite`, `android/app/src/main/java/com/smarteyes/Yolo.kt`; Modify `android/app/build.gradle.kts`; Test `android/app/src/test/java/com/smarteyes/YoloDecodeTest.kt`

**Interfaces — Produces:**
- `val COCO: List<String>` (80 labels, index order).
- `fun decodeYolo(out: FloatArray, anchors: Int, minConf: Float, iou: Float = 0.45f): List<Det>` — `out` is channels-first `[84][anchors]` flattened (`out[c*anchors + i]`), rows 0–3 = cx, cy, w, h normalised; rows 4–83 class scores. Per anchor take best class; keep ≥ minConf; class-wise NMS at `iou`.
- `class Yolo(context: Context) : Closeable { fun detect(bitmap: Bitmap): List<Det> }` — letterbox-free resize to 320×320, RGB float [0,1], GPU delegate with CPU fallback; reads output shape at runtime and asserts `[1, 84, N]`.

- [ ] **Step 1:** `tools/export_yolo.py`: `YOLO("yolo11n.pt").export(format="tflite", imgsz=320, half=True)`; copy the fp16 file into assets. Record output shape via Python TFLite interpreter.
- [ ] **Step 2: Failing tests** — `decodeKeepsBestClassAboveThreshold` (2 anchors, umbrella 0.9 at anchor 0, nothing at anchor 1 → one Det label umbrella), `decodeNmsDropsOverlap` (two overlapping umbrella boxes 0.9/0.8 → one), `decodeKeepsDifferentClasses` (overlapping umbrella + backpack → both).
- [ ] **Step 3:** `./gradlew testDebugUnitTest` → FAIL.
- [ ] **Step 4:** Implement `decodeYolo`, `COCO`, `Yolo`; add `org.tensorflow:tensorflow-lite` + `-gpu` deps; `androidResources { noCompress += "tflite" }`.
- [ ] **Step 5:** tests pass; on device, `Yolo.detect` on a photo with an umbrella returns `umbrella`, log inference ms.
- [ ] **Step 6: Commit** `feat(android): YOLO11n TFLite runner + decode`.

### Task 5: Wire YOLO + statuses + event logging into the app

**Files:** Modify `Hub.kt`, `Backend.kt`, `Player.kt`, `MainActivity.kt`, `VoiceRecorder.kt` (recording flag)

**Interfaces — Consumes:** Task 1 endpoint, Task 3 logic, Task 4 `Yolo`. **Produces:** `Backend.hazardEvent(source: String, label: String, confidence: Float?, latencyMs: Int, status: String)` (suspend, swallows all exceptions); `Player.cacheHazardClip(backend)` / `Player.playHazard()`; `Hub.recording: Boolean`; `Hub` exposes `var onYoloFrame: ((Bitmap, List<Det>, Det?, Long) -> Unit)?` for the debug view.

- [ ] **Step 1:** YOLO loop in `Hub.startHazards()`: every `YOLO_PERIOD_MS` on `Dispatchers.Default`, skip if busy; `latestJpeg` → bitmap → `yolo.detect` → `headHazard` → `tracker.update`; on warn → `quiet.decide(now, recording)` → SPOKEN: `player.playHazard()` (interrupts answer); post event (`yolo`, label, conf, latency, status). Emit `onYoloFrame`.
- [ ] **Step 2:** Gemini hazard path: `reply.hazard && say.isEmpty()` → `cooldown`; `reply.hazard` → `quiet.decide(now, recording)` → speak only if SPOKEN; post event (`gemini`, say, null, request ms, status). Non-hazard replies post nothing.
- [ ] **Step 3:** `Hub.recording` true during `recordQuestion()`.
- [ ] **Step 4:** Update `HubTest` for the new constructor; add `geminiHazardMutedAfterYolo` (inject a fake YOLO hit first) only if Hub stays JVM-testable; otherwise rely on Task 3 tests (ledger the ruling).
- [ ] **Step 5:** `./gradlew testDebugUnitTest assembleDebug` → OK; install; with umbrella photo on a monitor: one spoken warning, dashboard shows YOLO SPOKEN then Gemini MUTED.
- [ ] **Step 6: Commit** `feat(android): YOLO always-on hazard loop with shared quiet period + event log`.

### Task 6: Phone debug view

**Files:** Create `android/app/src/main/java/com/smarteyes/DebugView.kt`; Modify `MainActivity.kt`

- [ ] **Step 1:** `DebugView : View` draws the last frame bitmap scaled to width, a horizontal line at `TOP_ZONE`, each Det box (amber = hazard class, grey = other) with `label conf`, and a banner `WARN` / `—` plus inference ms. Fed from `Hub.onYoloFrame` on the main thread.
- [ ] **Step 2:** "Debug view" toggle button shows/hides it.
- [ ] **Step 3:** On device: boxes line up with objects; line at 40% height.
- [ ] **Step 4: Commit** `feat(android): YOLO debug overlay for on-site tuning`.

### Task 7: Test plan doc

**Files:** Create `docs/yolo-tuning-test-plan.md` — walk test (10–20 approaches, record fire distance), floor-umbrella negative test, person negative test, how each constant moves the fire distance, a results table template.

- [ ] **Step 1:** Write it. **Step 2: Commit** `docs: YOLO tuning test plan`.
