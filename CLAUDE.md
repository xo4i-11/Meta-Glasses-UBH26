# CLAUDE.md: handoff for the next session

You're picking up **Smart Eyes**, a hackathon project (assistive tech for blind users), on a **different Windows computer** from the one it was built on. The user is a student. They prefer casual, plain, step-by-step explanations, they're not an electronics expert, and they like interactive tools (they asked for the Cane Lab page). Read `README.md` first for the product overview, pin map and protocol. This file covers **state, decisions and gotchas**.

## Architecture (final, agreed with the user)
- **Cane** = one **ESP32-C3 SuperMini** with: HC-SR04 (forward), IR obstacle module (down, hole detection), ADXL345 (fall → SOS), 2-axis joystick + button, **8 Ω magnet speaker driven by a 2N2222A NPN**, SSD1306 OLED. It joins the phone hotspot and talks to the backend over **WebSocket** (`/ws/device?role=cane`). There is **no Bluetooth to the phone**: it was deliberately removed so the phone only holds the glasses' Bluetooth link.
- **Phone** (Pixel 8a, Kotlin) = glasses via Meta Wearables DAT 1.0.1, YOLO11n on-device (ONNX Runtime) announcing every 5–10 s (toggle), **Gemini only on a joystick press**, SMS SOS to the caregiver, YOLO memory log. It talks to the backend over HTTPS + WebSocket (`role=phone`).
- **Backend** (FastAPI) relays cane ⇄ phone, proxies Gemini/ElevenLabs, stores events/sightings (Tiger Data or SQLite), serves the dashboard.
- Dropped along the way: the wrist unit (merged into the cane), the Arduino UNO R4, vibration motors (they had none), BLE, and TFLite.

## Verified vs not (as of 2026-11-07)
| Item | Status |
|---|---|
| Backend | `tests_smoke.py` passes; `tests_relay.py` passes against a live uvicorn |
| Firmware `smart_cane`, `cane_lab`, `hardware_test` | Compile with arduino-cli, esp32 core **3.3.12**. `smart_cane` uses 93% of flash (default partition) |
| Android | `gradlew assembleDebug` succeeds, 0 warnings. **Never installed or run on a phone** |
| YOLO decode | `tools/verify_yolo_decode.py` matches Ultralytics on bus.jpg |
| Real hardware (via Cane Lab) | ✅ board, I2C (0x53 ADXL345 + 0x3C OLED), accel ≈1 g, ultrasonic tracks a hand, joystick X/Y full range. ❌ **joystick button never registered on pin 3** (the pin finder also saw nothing on 2/5/9; suspect a bad button or loose SW/GND, unresolved). ❓ speaker, OLED text, proximity alarm not yet confirmed by the user. ❌ IR module flickers mostly HIGH when face-down on a table (probably too close; needs 3–5 cm + pot tuning) |
| Glasses | ✅ **Working end to end (2026-11-07)** on the user's Pixel 8a + Ray-Ban Meta ("RB Meta 001J"): DAT session STARTED, raw stream OK (I420 colours correct), Gemini describe ~2.5 s, ElevenLabs voice ~3 s, YOLO 65–77 ms/frame on-phone. `createSession` returns "No eligible device available" for the first few seconds, so `GlassesCamera.start()` now retries every 3 s |
| YOLO v2 (2026-11-07) | Dynamic-shape `yolo11n.onnx`, letterbox, long side 416 (a portrait frame runs at 256×416). Startup benchmark on the Pixel 8a: **CPU×4 29 ms vs XNNPACK 55 ms**, and the app auto-picks the faster one. Frames are converted YUV→RGB directly (the old version did YUV→JPEG→Bitmap). `HazardEngine` summarises a 1.5 s window (an object must appear in 2+ frames), and `minScore` is 0.3. `tools/verify_yolo_decode.py` PASSes against Ultralytics |
| SOS | ✅ Holding the joystick button → "Text sent to your caregiver" with a fresh fused GPS fix (`Pointing.freshLocation`), then cancelled from the joystick. **The joystick button works now.** Fall flow: >2.5 g → settle 0.8 s → flat & still 1.2 s → locator siren + 5 s to press/move/pick up → SOS. The phone has a 9 s backup SOS if the cane's sos/cancel never arrives |
| Phone ⇄ cane end to end | ✅ Real cane on WiFi → backend → app ("Cane: online · 29 cm"). Fake-cane joystick presses (`C:\ye\fake_cane.py` on the old machine) trigger describe on the phone |

**Network setup that worked (University at Buffalo campus):** the laptop's Windows hotspot is forced onto 5 GHz while the laptop is on eduroam 5 GHz, and the ESP32-C3 only sees 2.4 GHz, so it was abandoned. Instead: the cane's MAC `10:00:3B:B1:20:A4` is registered on **UB_Devices** (WPA2-PSK, has 2.4 GHz APs), and the laptop is also on UB_Devices, so the cane reaches `ws://<laptop IP>:8000` directly. The **phone reaches the backend over USB** with `adb reverse tcp:8000 tcp:8000`, and the app's server address is `http://127.0.0.1:8000`. The server address is now a **runtime setting in the app** (bottom field + Save), so no rebuild is needed. Never commit the WiFi password: the old machine kept it in `C:\ye\cane_wifi.txt`, and `C:\ye\build_cane.py` injected it into a temp copy of the sketch.

## Pending decisions / next steps
1. Finish the hardware checks in Cane Lab (button, speaker, OLED, IR).
2. **Hole sensor:** the user was considering swapping the IR for a **2nd HC-SR04 pointing down**. If they say yes: TRIG → GPIO5, ECHO → 1 kΩ → GPIO10 (+2 kΩ to G); fire the two sensors alternately; hole if the floor is further than ~18 cm for 120 ms, clear under 15 cm (hysteresis); update `smart_cane.ino`, `cane_lab.ino`, `tools/cane-lab.html`, `tools/gen_wiring_page.py` → `docs/wiring.html`, and the README.
3. **Alarm distance:** the user found 150 cm "going crazy" on a desk, so `smart_cane` now uses `alertCm = 60` and `SOLID_CM = 15`. **The IR hole sensor is disabled** (`USE_HOLE_SENSOR 0`) at the user's request because it was bugging. The firmware prints `[wifi] …` status lines over USB serial.
4. **Phone + glasses** test ladder (README → Android): backend with keys → install APK → Phone camera → Mock glasses → real glasses → cane joystick end to end → SMS SOS.
5. Likely glasses fixes: the raw frame format (I420 assumed in `FrameUtils.i420ToBitmap`; if green/purple, swap U/V), and `frameRate = 15` in `GlassesCamera.kt` (try 7 or 24 if `addCamera` fails).
6. Done: the backend URL is a runtime setting in the app (SharedPreferences `server_url`; the default comes from `local.properties`).
7. Backend hardening done: the Gemini fallback has a 13 s total budget (the phone times out at 15 s), and models that reject `thinking_budget` (400 INVALID_ARGUMENT, e.g. flash-lite) are retried without it. The **free-tier key hits 429** quickly, so the fallbacks matter. `db.py` reconnects to Tiger Data after a WiFi change. Still untested: the joystick button on the real cane, SMS SOS, fall detection.

## Toolchain setup that worked (Windows; use SHORT paths)
The old machine used `C:\ye\`. It is **not** in this zip, so rebuild it as needed:
- **Python:** `py` launcher (3.13). Backend venv: `backend\.venv`.
- **arduino-cli 1.1.1** → `core install esp32:esp32` (with the Espressif index URL), plus `lib install "WebSockets" "ArduinoJson" "Adafruit SSD1306" "Adafruit GFX Library"`. FQBN: `esp32:esp32:esp32c3:CDCOnBoot=cdc`. Upload: `--upload -p COMx`. The board enumerates as USB VID `303A`.
- **Android:** Temurin JDK 17, Android cmdline-tools → `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`. Set `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`. `android/gradlew` is included. Create `android/local.properties` with `sdk.dir=…` and `backend_url=…`.
- **YOLO re-export (only if needed):** a venv at a short path (PyTorch hits Windows' 260-char limit), CPU torch + ultralytics → `tools/export_yolo.py` → ONNX.
- **Serial poking from scripts:** `pyserial` in any venv.

## Backend facts verified with real keys (2026-11-07)
- Gemini, ElevenLabs and Tiger Data all work end to end (describe about 2–4 s, read about 2 s, TTS about 0.3 s). Keys live only in `backend/.env`; it's git-ignored and not in the zip.
- **Gemini model availability:** `gemini-2.5-flash` returns 404 ("no longer available to new users"). `gemini-3.8-flash` and `3.7-flash` were timing out (overloaded). The default is now **`gemini-3.6-flash`**, with fallbacks `gemini-3.5-flash-lite, gemini-3.1-flash-lite, gemini-3.8-flash` (`GEMINI_FALLBACK_MODELS`). `gemini.py::_generate` falls through on 404/429/5xx/timeouts with a 7 s per-model cut-off; Google's minimum SDK timeout is 10 s. `/health` shows `gemini_last_used`, and `/api/describe` returns `model`. List what a key can use with `client.models.list()`.
- **The Tiger Data database is shared with a teammate** whose own version uses tables `events` (74 rows, different columns), `hazard_events` and `sightings`. Never touch those. Our tables are `smarteyes_events` / `smarteyes_sightings` (`DB_TABLE_PREFIX`). An empty `sightings` table with our old schema was created by an earlier run; it's harmless and was left in place. If the DB is unreachable, `db.py` falls back to SQLite instead of crashing.

## Gotchas learned the hard way
- **A COM port is single-owner.** If Cane Lab (Edge) is connected, uploads and pyserial fail with "Access denied". Ask the user to Disconnect or close the tab, or loop the upload until the port frees.
- **Ultralytics TFLite/LiteRT export is Linux/macOS only now**, which is why the app uses **ONNX** (`onnxruntime-android 1.22.0`), `assets/yolo11n.onnx`, 320×320, output `[1,84,2100]` in pixel units.
- **FastAPI TestClient deadlocks relaying between two WebSockets** (each socket runs its own event loop). Test the relay with `tests_relay.py` against a real uvicorn.
- **Bash heredocs mangle large Python patches** with mixed quotes or backslashes (a Kotlin regex `\\.` got collapsed once). Write patch scripts to files with the Write tool and run them.
- **Arduino auto-prototypes:** declare enums and types used in function signatures before the first function (`Mode` lives near the top of `smart_cane.ino`).
- **ESP32-C3 SuperMini WiFi:** `WiFi.setTxPower(WIFI_POWER_8_5dBm)` is already in the sketch (a known antenna quirk). GPIO 2/8/9 are strapping pins; leave them free.
- **The user's "IR sensor" was at one point a PIR.** A PIR can't detect floors. `USE_HOLE_SENSOR 0` disables hole logic.
- 2N2222A orientation: printed side facing you, legs down → E B C (most makers). Verify with a diode test if the sound is faint.
- **Visual checks:** headless Edge works for screenshots: `msedge --headless=new --screenshot=… --virtual-time-budget=… file:///…`. Cane Lab supports `#sim` to auto-run its simulation.
- The claude.ai wiring artifact is private to the original user; use `docs/wiring.html` (generated by `tools/gen_wiring_page.py`).

## Files you'll touch most
- `firmware/smart_cane/smart_cane.ino`: settings block at the top (WiFi, backend, `USE_HOLE_SENSOR`, `IR_ACTIVE_LOW`, `CANE_AXIS`), pins, `runSound`, `runFallLogic`, `pollJoystick`
- `firmware/cane_lab/cane_lab.ino` + `tools/cane-lab.html`: test harness (JSON lines over serial; commands `tone/sweep/beep/led/oled/scan/alarm/alarmcm`)
- `android/app/src/main/java/com/smarteyes/app/SmartEyesController.kt`: the app's brain
- `backend/app/main.py`: endpoints + `/ws/device` relay (`handle_cane`, `handle_phone`)
