# Large-Print Edition UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace the plain button list with the Large-Print Edition main screen: status chips, the "last said" page, a Guidance pill switch, three action tiles, a full-screen hazard flash, an offline banner, and a collapsible Helper tools section. Everything is TalkBack-first.

**Architecture:** Views built in code, as now. New `ui/` files hold the theme tokens and the custom widgets. Hub gains two optional callbacks so the screen gets structured state instead of parsing status strings.

**Spec:** `PRODUCT.md` + surface brief `.impeccable/surfaces/c-main-java-com-smarteyes-mainactivity-kt-1915a79f.md` (direction contract).

## Global Constraints

- Palette: `YELLOW #FFD400`, `INK #111111`, `PAGE #FFFFFF`. No grey type. Hazard state = black ground, yellow type.
- Type: Atkinson Hyperlegible Regular/Bold, self-hosted in `res/font`, sizes **13 / 20 / 31 sp only**.
- Borders: 3dp ink. No shadows. Touch targets ≥ 48dp; primary controls ≥ 96dp tall.
- Every control: `contentDescription` plus a state description. Status changes are announced once (`announceForAccessibility`). Haptic on every action.
- Voice: status = short ("Guidance on."); hazard = crisp ("Stop. Head height."); answers as returned.

## Tasks

### Task 1: Hub structured callbacks (TDD)

**Files:** `Hub.kt`, `HubTest.kt`

- **Produces:**
  - `onReply: (mode: String, say: String, latencyMs: Int) -> Unit = {}`, called when a non-hazard reply plays.
  - `onOnline: (Boolean) -> Unit = {}`: `true` after any successful request, `false` on `IOException`.
- **Tests:**
  - `describeReplyReportedToScreen`: `onReply("describe", "A door.", …)` called once.
  - `onlineFlagFollowsRequests`: an `IOException` gives `[false]`; a following success gives `[false, true]`.

### Task 2: Theme + font + widgets

**Files:** create `ui/LargePrint.kt` (tokens, `font()`, `dp()`, `outlined()` drawable), `ui/StatusChip.kt`, `ui/GuidancePill.kt`, `ui/ActionTile.kt`; `res/font/atkinson_regular.ttf`, `res/font/atkinson_bold.ttf` (OFL, from google/fonts).

- **StatusChip:** name 20sp bold plus a 13sp state line. Connected = ink fill with yellow text; otherwise yellow with an ink outline. Talks as "Glasses, connected".
- **GuidancePill:** 96dp, full width. On = ink fill, yellow text, knob on the right; off = yellow fill, ink outline, knob on the left. Exposed to accessibility as a Switch with state "On"/"Off".
- **ActionTile:** 118dp square-ish, white, 20dp radius. Label 20sp plus the cane gesture at 13sp. Pressed = ink fill with yellow text.

### Task 3: Main screen composition

**Files:** `MainActivity.kt`

- **Order:** offline banner (hidden by default), chips row, page (31sp sentence + 13sp meta line; empty state "Press Describe, or turn on Guidance."), Guidance pill, tiles row, "Helper tools" toggle with a section below it (camera source pill, Debug view pill, details text, DebugView).
- **Wiring:**
  - glasses / cane / server status → chips, announced once on change
  - `onReply` → page
  - `onOnline(false)` → banner "Server offline. Warnings still work."
  - `logHazard(status = SPOKEN)` → `flashHazard()`: full screen black, "Stop. Head height." at 31sp yellow, held 2.5 s
  - Guidance toggle → speaks "Guidance on." / "Guidance off."
- **Edge-to-edge:** window insets padding; dark status-bar icons on yellow.

### Task 4: Verify on Pixel

- Install. Screenshots: default, guidance on, offline banner, helper tools open, font scale 1.3. TalkBack labels checked via a uiautomator dump of `content-desc`.

### Task 5: Finish review + DESIGN.md (impeccable finish flow)
