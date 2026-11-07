# Surface brief: Smart Eyes main screen (Android)

Scope: the single main screen of the phone app (MainActivity) plus its secondary "Helper tools" panel. Mode: **Operate**.
Audience/job: blind user by touch + TalkBack (start/stop guidance, Describe / Read / Ask, hear status); sighted helper for connection, camera source, and YOLO tuning.
Constraints: native Kotlin Views built in code (no Compose), minSdk 31, Pixel 8a; every control ≥48dp with TalkBack label + state; sp text that follows system font scale; haptic confirm on actions; hazard speech is audio, never screen-dependent.
Decided: the yellow large-print ground ignores system dark mode (it is the accessibility choice, not a theme).

## Direction contract

THESIS: The phone screen is a large-print page. Smart Eyes' last spoken sentence is the screen's subject, set huge on a white page; status and actions surround it. It refuses the category default of a settings list of small switches and cards.

OWN-WORLD: Large-print yellow board (#FFD400) as ground, ink #111111, white page #FFFFFF with a hard 3dp ink outline (no shadow: depth is the outline, as on a printed page). Atkinson Hyperlegible only, at a strict three-step scale (13 / 20 / 31sp), bold for content. Pills and chunky rounded tiles with 3dp ink borders. State owns the whole ground: a hazard flips the full screen to black with yellow type; offline shows as an inked banner, never a small dot. Text is always pure ink on yellow or white: no grey type.

STORY: The user hears and feels that Smart Eyes is listening, starts guidance with one huge control, and repeats the cane's three actions from the screen. A helper sees at a glance which link is down and why.

FIRST VIEWPORT: Top: three status chips (Glasses, Cane, Server), filled ink when connected. Below: the page: a white sheet holding the last sentence at 31sp, with its source and age on one 13sp line below it (no label above). Then the Guidance pill switch, 96dp tall and full width. Then a row of three square action tiles (Describe / Read / Ask, each with its cane gesture). Last: a "Helper tools" link to a sheet with the camera source and debug view.

FORM: Large-Print Edition, position 4 on the grounded list; seed key 86732d72. Raised by the acetate manual (state owns the ground), Ikeda (total contrast) and Emigre (one face, strict scale).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
