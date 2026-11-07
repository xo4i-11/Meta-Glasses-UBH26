# Smart Eyes — Design

Date: 2026-11-07 · Owner (all software): Duy Nghiem · Source: "Project Proposal: Smart Eyes for the Blind" (Nov 6, 2026)

## Intent

Assistive system for blind users, built solo (software) at a hackathon with ~1 day left (hardware borrowed until Sunday 3:30 PM).
Ray-Ban Meta glasses + Pixel 8a + 3D-printed smart cane (Arduino UNO R4 WiFi, HC-SR04, active buzzer, one button).

The pitch: Meta AI waits to be asked and a white cane misses head height. Smart Eyes **speaks up unprompted** when something is at head height, while the cane beeps for low obstacles.

## Goals

1. **The demo moment (must be reliable):** blindfolded walker approaches a hanging object, nobody presses anything, glasses say "Stop, head height" (or similar). Simultaneously the cane beeps faster near a chair.
2. Describe on demand (button PRESS), read text (HOLD).
3. Memory: "Where are my keys?" (DOUBLE press → spoken question) answered from earlier camera observations.
4. Live dashboard for judges: event log, hazard list, per-request latency.
5. Hit every sponsor track genuinely: Gemini (all vision), ElevenLabs (TTS + Scribe STT), Vultr (backend host), Tiger Data (event/memory store), GoDaddy (domain).

## Non-goals

- On-phone YOLO (proposal had it; replaced by Gemini hazard timer).
- Sky Mode, SOS/fall detection (stretch only, after everything below works).
- Saving faces / identifying people.
- Accounts, auth beyond a shared secret, multi-user.
- Polished phone UI. The app is voice-first; screen shows status + a debug log only.

## Anti-goals (failure even if it "works")

- Hazard loop narrates constantly (talks when nothing is at head height).
- Same warning repeated back-to-back.
- Safety beep depends on phone or internet. Cane buzzer must be fully local.
- API keys shipped in the APK.
- Presented as a safety device. It is an assistant; user still uses cane skills.

## Constraints

- Phone: Pixel 8a, Kotlin, Android Studio on Windows (no Mac → Android only).
- Glasses link: Meta Wearables Device Access Toolkit (camera stream ≤720p). Known toolkit bug: stream settings can't change mid-session → pick one setting, keep it.
- Gemini: free AI Studio key(s). Team has 2–3 backup keys.
- Network: phone on mobile data, not venue Wi-Fi.
- Measure and log real latency; pitch uses measured numbers.
- Simple code; working beats perfect.

## Architecture

```
Glasses (camera, mic, speaker) ──BT──┐
                                     ├── Pixel 8a app (hub) ──HTTPS──► FastAPI on Vultr (Caddy, GoDaddy domain)
Cane R4 (BLE: PRESS/HOLD/DOUBLE) ──BLE┘                                  ├─ Gemini (vision → JSON)
Cane R4 buzzer ◄── HC-SR04 (local only)                                 ├─ ElevenLabs (TTS Flash, Scribe STT)
                                                                        ├─ Tiger Data (Postgres hypertable)
                                                                        └─ GET /events + static dashboard
```

### 1. Android app (hub)

- **Glasses camera:** Device Access Toolkit stream, keep latest frame in memory; grab on demand. Resize to ~512 px long edge, JPEG.
- **BLE client to R4:** subscribe to one notify characteristic; events `PRESS`, `HOLD`, `DOUBLE`. Auto-reconnect on drop.
- **PRESS** → `POST /frame mode=describe`. **HOLD** → `mode=read_text`. **DOUBLE** → record ~4 s audio (glasses BT mic via SCO, fall back to phone mic) → `POST /ask`.
- **Hazard timer:** every 4 s → `POST /frame mode=hazard`. Skip tick if any request is in flight (never queue). A button press cancels a pending hazard request and takes priority.
- **Feedback:** short phone vibration on every button press.
- **Audio out:** play returned MP3 via normal Android media playback; glasses are the BT audio sink so it comes out of the glasses speaker.
- **Offline:** backend unreachable/timeout → Android TTS: "Connection lost, the cane is still active." (rate-limited, not every tick).
- **Latency:** record press→audio-start per request; send to backend with next request or log locally.

### 2. Backend (Python FastAPI on Vultr)

- Caddy in front for HTTPS on the GoDaddy domain. Every request requires a shared-secret header (dashboard: secret in query string or same header via fetch).
- **`POST /frame`** (JPEG, `mode` ∈ describe | read_text | hazard)
  - Gemini returns JSON `{hazard: bool, say: str, objects: [str], caption: str}`.
  - Prompts: *describe* = one short sentence; *read_text* = read visible text; *hazard* = `hazard` true **only for obstacles at head height** (roughly chest-to-head level, in the walking path). Low/ground obstacles like chairs are explicitly NOT hazards — the cane covers those (decided by owner). `say` under 6 words.
  - `hazard=true` → return a pre-generated ElevenLabs clip ID (e.g. "Stop, head height", "Duck, head height"; generated at startup). Otherwise live ElevenLabs TTS (Flash model).
  - `hazard=false` in hazard mode → return no audio (silence).
  - Cooldown: same hazard not repeated within 10 s.
  - Response `{say, hazard, audio_id}`; `GET /audio/{id}` serves the MP3.
  - Every result logged to Tiger Data via FastAPI `BackgroundTasks` (not blocking the response).
- **`POST /ask`** (audio): ElevenLabs Scribe STT (empty → default "where are my keys?") → query recent rows whose `objects` match the asked item → Gemini phrases the answer ("Your keys are on the table near the door, 4 minutes ago") → TTS.
- **`GET /events`** + one static HTML dashboard served by FastAPI, polling every 2 s: live log, hazard list, latency per request.

**Table (Tiger Data hypertable on `ts`):** `ts timestamptz, mode text, say text, objects text[], caption text, hazard bool, latency_ms int`.

### 3. R4 firmware (Arduino, ArduinoBLE)

- Read HC-SR04 continuously; buzzer beeps faster as distance drops below 150 cm. Local only, no phone involvement.
- Button: detect PRESS, HOLD (>700 ms), DOUBLE; send over one BLE characteristic with notifications.
- **Owned by the hardware teammate** (decided by owner). This software owner only consumes the BLE contract below; hand it to the teammate first thing.

**BLE contract (proposed by Claude, teammate to confirm):**
- Device local name: `SmartCane`
- Service UUID: `709dd8c2-e2ae-492e-bcbc-ab2ad425d950`
- Event characteristic UUID: `51cfb510-0815-4e68-aabc-256bbb5a64fb` — properties Read + Notify, 1 byte
- Values: `1` = PRESS, `2` = HOLD (>700 ms), `3` = DOUBLE (two presses within ~400 ms). Firmware notifies once per event.

## Build order

0. **Provision (nothing exists yet, decided by owner):** Gemini keys (AI Studio, plus teammates' backup keys), ElevenLabs key, Tiger Data instance, Vultr VM, GoDaddy domain → A record to Vultr IP. Start the domain first (DNS lag). Until DNS + Caddy HTTPS are live, the app talks to the VM by IP over plain HTTP (dev only; Android cleartext allowed for that IP in debug). Backend runs locally on the laptop until the VM is up. Tiger Data logging is a no-op if the DB isn't reachable yet.
1. Core pipe: PRESS (on-screen button until BLE exists) → frame → `/frame` → Gemini → TTS → glasses audio.
2. Hazard timer + cached clips + cooldown (the demo moment). Tune hazard prompt on 20–30 photos of the exact stage setup (large high-contrast hanging object, stage lighting).
3. R4 firmware + BLE link.
4. Tiger Data logging, `/ask` memory, dashboard.
5. Rehearse 3×, record backup video.

**Day-1 first checks:** Meta sample app streams video on the Pixel; glasses play phone audio while a camera session is active; glasses + R4 connected over BT simultaneously.

## Decisions made by Claude (check these)

- Python FastAPI (not Node) for backend.
- On-screen debug buttons in the app mirroring PRESS/HOLD/DOUBLE so development and demo don't depend on BLE.
- Hazard-mode `hazard=false` returns no audio at all.
- `latency_ms` column added to the table for the dashboard.
- Gemini model choice + key rotation left to builder (see open risk below).
- **Swappable frame source + audio out.** Frame source: glasses stream, fallback phone rear camera. Audio out: glasses BT, fallback phone speaker/wired earbuds. BLE fallback: on-screen buttons. Go/no-go on glasses stream after ~1 h of trying; then switch to fallback and keep building.
- **Memory input:** every mode (incl. hazard) returns `objects` as short "item on/near place" strings, so hazard ticks feed memory too. Demo script still does one PRESS describe near the keys before the memory question as insurance.
- **Deadline unknown** → cut line assumed after step 2 (hazard moment). Steps 3–4 only start once step 2 works end to end on the real glasses.
- **Hazard cadence** is a config value (default 4 s). Demo approach is staged slow (walker stops ~3 m out, then steps in) instead of raising cadence into the rate limit.

## Open risks

- **Gemini free-tier rate limit:** hazard every 4 s = 15 req/min plus button requests; may exceed free-tier RPM for some models. Builder picks a model whose free RPM fits, or rotates the backup keys.
- **Audio routing with SCO:** opening SCO for the mic switches the glasses to call-mode audio; playback after `/ask` must close SCO first.
- **Toolkit + BLE + BT audio at once** on one phone untested.

## Left to the builder

Exact Gemini model/prompts wording, BLE UUIDs, HTTP client library, code layout, dashboard styling, Caddy/systemd setup details, how phone ships latency to backend.
