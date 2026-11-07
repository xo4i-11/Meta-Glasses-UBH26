# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

- **Primary: a blind person walking with the Smart Eyes cane and Ray-Ban Meta glasses.** They use the phone app by touch and TalkBack (spoken labels, big targets), mostly while standing or walking, often with the phone in one hand and the cane in the other. Their job: move safely, and on demand understand what is in front of them, read text, or find things they put down.
- **Secondary: a sighted helper or teammate.** They handle setup, glasses pairing, connection problems, tuning on site, and run the live demo for hackathon judges.

## Product Purpose

Smart Eyes warns a blind user about obstacles at head height, which a white cane cannot detect, without being asked. It also describes scenes, reads text and remembers where things were seen, all on demand. Success: the user hears a short, timely warning before reaching a head-height obstacle, and gets a useful answer within a few seconds when they ask.

## Positioning

Meta AI on the glasses waits to be asked. Smart Eyes speaks up on its own. On-phone YOLO plus the cane's own distance sensor keep safety warnings working with no internet; Gemini in the cloud is the smarter second opinion and the voice for descriptions.

## Operating Context

- **Hardware:** Pixel 8a (Android), Ray-Ban Meta glasses (camera, mic, speaker) via the Meta Wearables Device Access Toolkit, and a 3D-printed cane with an Arduino UNO R4 (HC-SR04 buzzer, one button sending PRESS / HOLD / DOUBLE over BLE).
- **Use:** usually the phone is in a pocket and the glasses speak. The screen is used to start things, check status, or fix a connection.
- **Backend:** FastAPI (Vultr), Gemini, ElevenLabs voice, and a Tiger Data event log. The dashboard is a separate web page for judges and is not part of the phone app.
- **Demo:** a blindfolded walker approaches a hung umbrella and a chair while judges watch the dashboard. Venues are noisy, so the phone vibrates to confirm presses.

## Capabilities and Constraints

- **Hazard guidance (on/off):** YOLO on the phone every 400 ms plus Gemini every 4 s. Warns once per obstacle, then a 5 s shared quiet period. Head-height umbrella or backpack by default.
- **On-demand actions** (same as the cane button): Describe (press), Read text (hold), Ask by voice (double press, e.g. "where are my keys?").
- **Status the user needs:** glasses (camera stream), cane (BLE), server (reachable). Offline: the cane buzzer and YOLO keep working; the app says "Connection lost, the cane is still active." at most every 30 s.
- **Tuning view:** live camera frame with YOLO boxes, the head-height line and the warning banner. For helpers only; secondary.
- **Camera source:** glasses (default) or phone camera as a fallback.
- **Stack:** native Kotlin, minSdk 31, currently plain Views built in code (no Compose, no XML layouts). Must keep working on the Pixel 8a.

## Brand Commitments

- **Name:** Smart Eyes.
- **Voice (owner-confirmed):**
  - Descriptions and answers: a **calm companion**, warm and short.
  - Hazard warnings: **always crisp**, 2–4 words, no filler ("Stop. Head height.").
  - Status messages: **short** ("Guidance on. Cane connected.").
  - No small talk. Never repeat what the user already knows.

## Evidence on Hand

- Measured on the Pixel 8a with real glasses: YOLO 89 ms per pass on glasses frames; Describe about 3 s; Gemini hazard checks about 1.4–3.2 s.
- No user testing with blind or low-vision people yet. Do not claim any.

## Product Principles

1. **Safety speaks first.** Hazard warnings pre-empt everything and are never decorated.
2. **Hands and ears, not eyes.** Every function is reachable by touch and TalkBack and confirmed by sound or vibration. The screen is a bonus.
3. **Say less.** Status changes are announced once and briefly. Silence means nothing changed.
4. **Works offline where it matters.** Losing the internet degrades descriptions, never warnings.
5. **Honest state.** The app always shows and speaks what is connected and what isn't. It never pretends.

## Accessibility & Inclusion

- **TalkBack first:** every control has a spoken label and state ("Guidance, on"). Live status changes are announced politely; hazard warnings come through audio, not the screen reader.
- **Touch targets:** large, at least 48 dp, with the primary controls much bigger, reachable one-handed, and none that depend on precise positioning.
- **Low vision:** high contrast, large text that respects the system font size, and meaning never carried by colour alone.
- **Feedback:** haptic confirmation for every action.
