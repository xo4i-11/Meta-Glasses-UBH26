# YOLO head-height warning: tuning test plan

All knobs are in `android/app/src/main/java/com/smarteyes/HazardConfig.kt`. Change a value, then rebuild and install (`cd android && ./gradlew installDebug`, about 30 s).

## Setup (5 min)

1. Hang the **open, brightly coloured umbrella** at head height (bottom edge about at eye level) in the demo path. Keep the backpack on a hook as a backup.
2. Mark the floor with tape at **1, 1.5, 2, 2.5, 3 m** from the umbrella.
3. Glasses on the walker. Phone app: **Camera → glasses**, **Hazard warnings: on**, **Debug view: on**.
4. Dashboard open on the laptop (`/?key=…`), so you can watch the timeline.

The debug view shows the frame YOLO saw, its boxes (amber = hazard class, grey = other), the dashed head-height line, and a banner: `WARN umbrella 82% → SPOKEN` or `no head-height hazard`. Each box is labelled with its confidence and `a=` area.

## Test 1: fire distance (10–20 walks)

Walk slowly toward the umbrella from 4 m. For each walk, write down the tape mark you were passing when the phone said "Stop, head height". The dashboard timeline gives the exact time.

| # | Fired at (m) | Conf % | Area `a=` | Gemini status | Notes |
|---|---|---|---|---|---|
| 1 | | | | | |
| … | | | | | |

**Target:** it fires between **1.5 and 2 m** on at least 9 of 10 walks.

## Test 2: floor umbrella must NOT warn

Put the umbrella on the floor in the path, open or closed. Walk toward it 5 times.

**Pass:** no spoken YOLO warning, and the banner never says `WARN`. The box centre (dot) stays below the dashed line. The cane handles this one.

## Test 3: person must NOT warn

A teammate stands in the path. Walk toward them 3 times. **Pass:** no YOLO warning (people are on the ignore list).

## Test 4: warn once

Stop under the umbrella and stand still for 15 s. **Pass:** exactly one spoken warning. Step back about 3 m, wait 2 s, then walk in again. **Pass:** it warns again.

## Test 5: offline

Turn off mobile data and Wi-Fi on the phone, then walk in. **Pass:** YOLO still says "Stop, head height" (cached clip), and you hear "Connection lost, the cane is still active" at most once every 30 s.

## How to tune

| Problem | Change |
|---|---|
| Fires too **late** (closer than 1.5 m) | Lower `MIN_AREA` (e.g. 0.05 → 0.03) |
| Fires too **early** (farther than 2 m) | Raise `MIN_AREA` (0.05 → 0.08) |
| Never fires, banner shows the box below the dashed line | Raise `TOP_ZONE` (0.40 → 0.50), or hang the umbrella higher |
| Floor umbrella fires | Lower `TOP_ZONE` (0.40 → 0.30) |
| Umbrella box flickers or is missing | Lower `MIN_CONF` (0.40 → 0.30); more light; brighter umbrella |
| Random objects fire | Raise `MIN_CONF`, or make sure only `umbrella`/`backpack` are in `HAZARD_CLASSES` |
| Repeats too often | Raise `REARM_FRAMES` (4 → 6) |
| Fires on a 1-frame glitch | Raise `PERSIST_FRAMES` (2 → 3) |

Write the final values here, with the date and the stage lighting:

```
TOP_ZONE = 
MIN_AREA = 
MIN_CONF = 
```

**Reading `a=`:** walk to your target fire distance, read the umbrella's `a=` value on the debug view, and set `MIN_AREA` slightly below it.
