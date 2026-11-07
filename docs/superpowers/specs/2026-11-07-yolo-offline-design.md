# Smart Eyes — On-phone YOLO hazard warnings (design)

Date: 2026-11-07 · Owner: Duy Nghiem · Extends: `2026-11-07-smart-eyes-design.md` (that spec listed on-phone YOLO as a non-goal; this reverses it)

## Intent

Add a fast, offline hazard warning on the phone. YOLO runs **all the time** on the newest camera frame and speaks a warning the moment something big is near the top of the picture. Gemini keeps running every 4 s as the **second opinion**: it catches what YOLO can't recognise (towels, branches) and phrases warnings better. Safety warnings no longer depend on the internet. It also strengthens the "Best AI/ML" pitch (on-device model + cloud model).

## Goals

1. Head-height warning with no internet: phone speaks "Careful, head height" (or the cached "Stop, head height" clip) when the rule below fires.
2. Fast: frame → decision well under 1 s on the Pixel 8a.
3. Works with both frame sources (glasses stream and phone camera).
4. No double-talk between YOLO and Gemini.

## Non-goals

- Distance estimation (no depth model). "Close" = box is large in the frame.
- Custom training / new classes. Stock COCO-80 model only.
- Logging YOLO warnings to the backend/dashboard.
- Warning about people (decided by owner): people are left to the cane and Gemini.

## Anti-goals

- Warning constantly in a normal room (false positives every few seconds).
- YOLO and Gemini announcing the same obstacle back to back.
- YOLO inference blocking the UI thread, button handling, or the Gemini hazard loop.

## Decisions (owner)

- YOLO runs all the time while "Hazard warnings" is on; Gemini stays as second opinion on its 4 s timer.
- Trigger: anything close in the top of the frame.
- Ignore the `person` class.

## Behaviour

**Trigger rule** (all must hold, on one frame):
- class ≠ `person`
- confidence ≥ `MIN_CONF` (default 0.40)
- box top edge in the upper `TOP_ZONE` of the frame (default: top 40%, i.e. `y1 ≤ 0.40` normalised)
- box area ≥ `MIN_AREA` of the frame (default 0.12)
- the rule held on **2 consecutive** YOLO frames (anti-flicker)

All four numbers live as named constants in one place so they can be tuned on stage props.

**Cadence:** YOLO runs on the newest frame every ~400 ms; if the previous inference is still running, skip (never queue). Runs off the main thread.

**Speech + shared cooldown (phone side):**
- One `HazardCooldown` on the phone shared by YOLO and Gemini hazard replies: after any hazard is spoken, further hazard speech is suppressed for 5 s. (The backend's existing 10 s per-clip cooldown stays.)
- YOLO speaks the cached `hazard_stop` clip, downloaded once from `GET /audio/hazard_stop` when the app starts (with the shared secret) and kept as a local file. If not cached (backend never reached), Android TTS says "Careful, head height."
- Button requests (describe/read/ask) are unaffected and still pre-empt the Gemini hazard request as today. YOLO speech does not go through the request `Gate` (it makes no network call).

**Online/offline:** YOLO behaves identically either way. Offline, Gemini opinions stop and the existing throttled "Connection lost, the cane is still active." still applies.

## Model

- YOLO nano (Ultralytics YOLO11n, or YOLOv8n if export issues), COCO-80, exported to TFLite float16, input 320×320 (speed over small-object accuracy; head-height obstacles are large by definition).
- Exported once on the laptop by a script committed in the repo (`tools/export_yolo.py`), output committed as an app asset (~5–6 MB).
- Run with LiteRT / TensorFlow Lite on Android; GPU delegate if it loads, else CPU (XNNPACK).

## Constraints

- Android, Kotlin, minSdk 31, Pixel 8a. Existing `FrameSource.latestJpeg()` provides 512 px JPEGs; the builder may add a cheaper bitmap accessor if decoding JPEG per frame is too slow.
- Must not change backend API.
- Keep existing tests green.

## Testing

- Unit (JVM): the trigger rule as a pure function over a list of detections (person ignored, top zone, area, confidence, 2-frame persistence) and the shared 5 s cooldown.
- Unit (JVM): YOLO output decoding (tensor → boxes + NMS) on a small synthetic tensor.
- On device: inference latency logged to the status line; a photo of a big object near the top triggers, a chair on the floor and a person do not.

## Decisions made by Claude (check these)

- Constants: `MIN_CONF` 0.40, `TOP_ZONE` 0.40, `MIN_AREA` 0.12, 2-frame persistence, 400 ms cadence, 5 s shared cooldown.
- 320 px input, float16 TFLite, nano model.
- YOLO uses the cached ElevenLabs `hazard_stop` clip so the voice matches; TTS fallback text "Careful, head height."
- Model file committed to the repo as an app asset.
- **Warn once per obstacle:** YOLO speaks once when the rule starts holding, then stays quiet until the rule has been false for 4 consecutive YOLO frames (~1.6 s) — re-arms only after the obstacle leaves. Prevents repeating every 5 s while standing in front of it.
- **Shared 5 s cooldown drops any hazard speech**, regardless of source or clip (YOLO "Stop" then Gemini "Duck" within 5 s → Gemini is silent).
- **Audio overlap:** while a voice question is recording (SCO open), YOLO speech is dropped (not queued). Otherwise a YOLO warning interrupts any answer that is playing — safety beats description.
- **Ignored classes:** a single `IGNORED_CLASSES` constant, initially `{person}`; extending it (e.g. `tv`) is a one-line tuning change, not a redesign. Threshold tuning on real-room photos happens in the demo-hardening step.

## Left to the builder

TFLite runtime artifact choice (LiteRT vs `org.tensorflow:tensorflow-lite`), output tensor layout handling for the exported model, NMS details, threading primitive, how the cached clip is stored/played, exact file names.

## Revision 2 (owner, 2026-11-07) — supersedes conflicting lines above

**Demo prop:** open, brightly coloured umbrella hung at head height (backup: backpack on a hook). Both are COCO classes, so YOLO and Gemini should both detect it.

**Kept:** warn once per obstacle (re-arm after ~1.6 s gone); 5 s quiet period after any spoken hazard drops every other hazard from any source; YOLO silent while a voice question records, otherwise YOLO cuts off a playing answer; ignore list starting `["person"]`.

**Changes:**
1. **Head-height filter:** warn only when the box **centre** is in the upper ~40% of the frame (`TOP_ZONE`, replaces "top edge") and box area ≥ `MIN_AREA` (≈ 1.5–2 m away). `TOP_ZONE`, `MIN_AREA`, `MIN_CONF` are constants at the top of one file.
2. **Hazard classes:** YOLO warns only for classes in `HAZARD_CLASSES`, default `["umbrella", "backpack"]`, tunable (replaces "any class").
3. **Muted confirmations:** a Gemini hazard inside the quiet period is not spoken but still logged with status `muted` and its latency.
4. **Hazard event log:** every hazard event logs `ts, source (yolo|gemini), label, confidence, latency_ms, status (spoken|muted|cooldown)` to Tiger Data. YOLO events are now logged (reverses the non-goal). The phone decides status, so the phone posts every hazard event to the backend (`POST /hazard_event`); offline events are dropped.
   - `cooldown` = the backend's own per-clip cooldown silenced a Gemini hazard (reply `hazard=true`, empty `say`). `muted` = phone quiet period or recording.
   - Gemini events: label = Gemini's `say` text, confidence = null, latency = phone-measured request time. YOLO events: latency = frame grab → decision.
5. **Dashboard timeline:** hazard events as a live timeline, YOLO vs Gemini distinguishable at a glance, e.g. `0.1 s  YOLO  umbrella ahead  SPOKEN` / `2.4 s  Gemini  umbrella, head height  MUTED (quiet period)`. Offsets are relative to the first event of a burst (a gap > 10 s starts a new burst).
6. **Phone debug screen:** live frame with YOLO boxes, the head-height zone line, confidence, and whether a warning fired — for tuning on site.

**Testing (owner):** walk toward the hung umbrella 10–20 times from the glasses' view, record at what distance YOLO fires, tune the constants; an umbrella on the floor must NOT trigger.
