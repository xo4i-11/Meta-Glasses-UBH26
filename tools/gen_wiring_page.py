"""Smart Eyes wiring page v2: ESP32-C3 SuperMini cane + glasses/phone architecture."""
import html
import sys

out_path = sys.argv[1]


def esc(s):
    return html.escape(str(s))


class S:
    def __init__(self):
        self.parts = []

    def add(self, s):
        self.parts.append(s)

    def text(self, x, y, s, cls="t-cap", anchor="middle"):
        self.add(f'<text class="{cls}" x="{x}" y="{y}" text-anchor="{anchor}">{esc(s)}</text>')

    def box(self, x, y, w, h, title, sub=None, caption=None, cls="comp"):
        self.add(f'<rect class="{cls}" x="{x}" y="{y}" width="{w}" height="{h}" rx="6"/>')
        self.text(x + w / 2, y + 22, title, "t-title")
        if sub:
            self.text(x + w / 2, y + 38, sub, "t-sub")
        for i, line in enumerate([caption] if isinstance(caption, str) else (caption or [])):
            self.text(x + w / 2, y + h + 16 + i * 14, line)

    def pin(self, x, y, label, side):
        self.add(f'<rect class="pin" x="{x - 3}" y="{y - 3}" width="6" height="6"/>')
        if side == "L":
            self.text(x + 9, y + 4, label, "t-pin", "start")
        elif side == "R":
            self.text(x - 9, y + 4, label, "t-pin", "end")
        else:  # bottom edge, label above
            self.text(x, y - 9, label, "t-pin")

    def wire(self, pts, net):
        self.add(f'<polyline class="w {net}" points="{" ".join(f"{a},{b}" for a, b in pts)}"/>')

    def path(self, d, net):
        self.add(f'<path class="w {net}" d="{d}"/>')

    def dot(self, x, y, net):
        self.add(f'<circle class="dot {net}" cx="{x}" cy="{y}" r="4"/>')

    def tag(self, x, y, net, direction):
        label = {"p5": "5V", "p33": "3.3V", "gnd": "GND"}[net]
        w = 44
        if direction == "L":
            self.wire([(x, y), (x - 16, y)], net)
            rx, ry = x - 16 - w, y - 10
        elif direction == "R":
            self.wire([(x, y), (x + 16, y)], net)
            rx, ry = x + 16, y - 10
        else:  # down
            self.wire([(x, y), (x, y + 16)], net)
            rx, ry = x - w / 2, y + 16
        self.add(f'<rect class="tag {net}" x="{rx}" y="{ry}" width="{w}" height="20" rx="10"/>')
        self.text(rx + w / 2, ry + 14, label, f"t-tag {net}")

    def resistor_h(self, x1, x2, y, label, net, label_dy=-13):
        seg = (x2 - x1) / 8
        pts = [(x1, y)] + [(x1 + seg * i, y + (-6 if i % 2 else 6)) for i in range(1, 8)] + [(x2, y)]
        self.wire(pts, net)
        self.text((x1 + x2) / 2, y + label_dy, label)

    def resistor_v(self, x, y1, y2, label, net):
        seg = (y2 - y1) / 8
        pts = [(x, y1)] + [(x + (-6 if i % 2 else 6), y1 + seg * i) for i in range(1, 8)] + [(x, y2)]
        self.wire(pts, net)
        self.text(x + 12, (y1 + y2) / 2 + 4, label, "t-cap", "start")

    def svg(self, w, h, label, min_width=760):
        return f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="{esc(label)}" style="min-width:{min_width}px">' + "".join(self.parts) + "</svg>"


# ============================================================================== cane wiring (real SuperMini layout)
c = S()
BX, BY, BW, BH = 430, 64, 140, 452
PIN_Y = [130 + i * 48 for i in range(8)]
LEFT = ["5", "6", "7", "8", "9", "10", "20", "21"]
RIGHT = ["5V", "G", "3.3", "4", "3", "2", "1", "0"]
L = dict(zip(LEFT, PIN_Y))
R = dict(zip(RIGHT, PIN_Y))
LX, RX = BX, BX + BW

c.text(BX + BW / 2, 22, "ESP32-C3 SuperMini", "t-board")
c.text(BX + BW / 2, 38, "top view · USB-C up · pin labels as printed", "t-boardsub")
c.add(f'<rect class="board" x="{BX}" y="{BY}" width="{BW}" height="{BH}" rx="8"/>')
c.add(f'<rect class="usb" x="{BX + 40}" y="{BY - 12}" width="60" height="34" rx="5"/>')
c.text(BX + BW / 2, BY + 10, "USB-C", "t-chipw")
for i, lab in enumerate(["BOOT", "RST"]):
    bx = BX + 38 + i * 40
    c.add(f'<rect class="btn" x="{bx}" y="{BY + 36}" width="24" height="16" rx="3"/>')
    c.text(bx + 12, BY + 64, lab, "t-chip")
c.add(f'<rect class="chip" x="{BX + 44}" y="{BY + 210}" width="52" height="52" rx="4" transform="rotate(45 {BX + 70} {BY + 236})"/>')
c.text(BX + 70, BY + 240, "C3", "t-chipw")
c.add(f'<rect class="ant" x="{BX + 34}" y="{BY + BH - 40}" width="72" height="22" rx="3"/>')
c.text(BX + 70, BY + BH - 25, "antenna", "t-chip")
for lab, y in L.items():
    c.add(f'<circle class="hole" cx="{LX + 12}" cy="{y}" r="6"/>')
    c.text(LX + 24, y + 5, lab, "t-silk", "start")
for lab, y in R.items():
    c.add(f'<circle class="hole" cx="{RX - 12}" cy="{y}" r="6"/>')
    c.text(RX - 24, y + 5, lab, "t-silk", "end")
for lab, note in [("5", "free"), ("8", "blue LED: leave free"), ("9", "BOOT: leave free")]:
    c.text(LX - 10, L[lab] + 4, note, "t-free", "end")
c.text(RX + 10, R["2"] + 4, "boot pin: leave free", "t-free", "start")


def lw(pts, net):  # wire starting at a left header hole
    c.wire([(LX + 12, pts[0][1])] + pts, net)


def rw(pts, net):  # wire starting at a right header hole
    c.wire([(RX - 12, pts[0][1])] + pts, net)


# power out of the right header
for lab, net in [("5V", "p5"), ("G", "gnd"), ("3.3", "p33")]:
    rw([(RX, R[lab])], net)
    c.tag(RX, R[lab], net, "R")

# ---- left: ADXL345 + OLED on I2C (6 SDA, 7 SCL)
c.box(80, 30, 140, 125, "ADXL345", "accelerometer", caption="Y arrow along the shaft")
c.pin(220, 95, "SDA", "R")
c.pin(220, 125, "SCL", "R")
for y, lab, net in [(85, "VCC", "p33"), (103, "GND", "gnd"), (121, "CS", "p33"), (139, "SDO", "gnd")]:
    c.pin(80, y, lab, "L")
    c.tag(80, y, net, "L")
c.box(80, 190, 140, 90, "OLED SSD1306", "128×64")
c.pin(220, 245, "SDA", "R")
c.pin(220, 265, "SCL", "R")
c.pin(80, 245, "VCC", "L")
c.tag(80, 245, "p33", "L")
c.pin(80, 265, "GND", "L")
c.tag(80, 265, "gnd", "L")
# SDA bus x=320 (95..245), SCL bus x=290 (125..265)
lw([(LX, L["6"]), (320, L["6"])], "i2c")
c.wire([(320, 95), (320, 245)], "i2c")
c.wire([(320, 95), (220, 95)], "i2c")
c.path("M320 245 H296 A6 6 0 0 0 284 245 H220", "i2c")  # hop over the SCL bus
c.dot(320, L["6"], "i2c")
c.wire([(LX + 12, L["7"]), (LX, L["7"])], "i2c")
c.path(f"M{LX} {L['7']} H326 A6 6 0 0 0 314 {L['7']} H290", "i2c")  # hop over the SDA bus
c.wire([(290, 125), (290, 265), (220, 265)], "i2c")
c.wire([(290, 125), (220, 125)], "i2c")
c.dot(290, L["7"], "i2c")

# ---- left: HC-SR04, TRIG 20, ECHO 21 through the divider
c.box(80, 440, 140, 110, "HC-SR04", "ultrasonic", caption="on the shaft, facing forward")
c.pin(220, 470, "TRIG", "R")
c.pin(220, 520, "ECHO", "R")
c.pin(80, 480, "VCC", "L")
c.tag(80, 480, "p5", "L")
c.pin(80, 520, "GND", "L")
c.tag(80, 520, "gnd", "L")
lw([(LX, L["20"]), (370, L["20"]), (370, 470), (220, 470)], "sig")
lw([(LX, L["21"]), (400, L["21"]), (400, 520)], "sig")
c.dot(400, 520, "sig")
c.wire([(400, 520), (400, 532)], "sig")
c.resistor_v(400, 532, 572, "2 kΩ", "sig")
c.tag(400, 572, "gnd", "D")
c.wire([(400, 520), (340, 520)], "sig")
c.resistor_h(280, 340, 520, "1 kΩ", "p5sig", label_dy=20)
c.wire([(280, 520), (220, 520)], "p5sig")
c.text(310, 556, "5V echo → 3.3V", "t-cap")

# ---- right: speaker driver on 4
cx, cy = 702, R["4"]
rw([(RX, cy), (590, cy)], "sig")
c.resistor_h(590, 650, cy, "1 kΩ", "sig")
c.wire([(650, cy), (690, cy)], "sig")
c.add(f'<circle class="npn" cx="{cx}" cy="{cy}" r="22"/>')
c.add(f'<line class="w sig" x1="690" y1="{cy - 12}" x2="690" y2="{cy + 12}"/>')
c.wire([(690, cy - 6), (710, cy - 19), (710, 175), (770, 175)], "spk")
c.wire([(690, cy + 6), (710, cy + 19), (710, 300)], "gnd")
c.add(f'<path class="arrowhead" d="M710,{cy + 19} l-7,-1 l3,-6 z"/>')
c.tag(710, 300, "gnd", "R")
c.text(740, 262, "NPN", "t-pin", "start")
c.text(740, 276, "2N2222 / S8050", "t-cap", "start")
c.box(770, 150, 100, 50, "Speaker", None)
c.pin(770, 175, "−", "L")
c.pin(870, 175, "+", "R")
c.wire([(870, 175), (880, 175)], "p5")
c.resistor_h(880, 930, 175, "22 Ω", "p5")
c.tag(930, 175, "p5", "R")
c.text(838, 218, "diode across speaker (stripe → +)", "t-cap")
c.text(838, 232, "100 µF from 5V to GND nearby", "t-cap")

# ---- right: joystick on 3 (SW), 1 (VRy), 0 (VRx)
c.box(780, 345, 140, 150, "Joystick", "thumb, on the handle", caption="its “+5V” pin goes to 3.3V!")
c.pin(780, 400, "SW", "L")
c.pin(780, 435, "VRy", "L")
c.pin(780, R["0"], "VRx", "L")
c.pin(920, 400, "+5V", "R")
c.tag(920, 400, "p33", "R")
c.pin(920, 440, "GND", "R")
c.tag(920, 440, "gnd", "R")
rw([(RX, R["3"]), (750, R["3"]), (750, 400), (780, 400)], "sig")
rw([(RX, R["1"]), (730, R["1"]), (730, 435), (780, 435)], "ana")
rw([(RX, R["0"]), (780, R["0"])], "ana")

cane_svg = c.svg(1000, 620, "Wiring diagram for the Smart Eyes cane on an ESP32-C3 SuperMini, drawn with the board's real pin order")

# ============================================================================== architecture
a = S()
a.add('<defs><marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="ahead" d="M0,0 L10,5 L0,10 z"/></marker></defs>')


def arrow(x1, y1, x2, y2, both=False):
    st = ' marker-start="url(#ah)"' if both else ""
    a.add(f'<line class="flow" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" marker-end="url(#ah)"{st}/>')


a.add('<rect class="wifi" x="10" y="14" width="940" height="356" rx="16"/>')
a.add('<rect class="island" x="24" y="30" width="262" height="320" rx="12"/>')
a.text(44, 54, "EYES", "t-island", "start")
a.box(44, 68, 222, 62, "Ray-Ban Meta glasses", "camera · mic · speaker")
arrow(84, 130, 84, 192, both=True)
a.text(96, 166, "Bluetooth (only link)", "t-cap", "start")
a.box(44, 196, 222, 136, "Phone app (Pixel)", "the brain")
for i, line in enumerate(["YOLO every frame, offline", "announces every 5–10 s", "Gemini when joystick asks", "texts caregiver on SOS"]):
    a.text(155, 254 + i * 17, line, "t-note")

a.add('<rect class="hub" x="380" y="30" width="200" height="320" rx="12"/>')
a.text(480, 56, "Backend on Vultr", "t-title")
a.text(480, 72, "the relay everything talks to", "t-sub")
for i, (t, s2) in enumerate([("Cane ⇄ phone relay", "WebSocket"), ("Gemini", "only on joystick press"), ("ElevenLabs", "natural voice"), ("Tiger Data", "memory + hazard log")]):
    y = 92 + i * 62
    a.add(f'<rect class="inner" x="398" y="{y}" width="164" height="50" rx="6"/>')
    a.text(480, y + 21, t, "t-pin")
    a.text(480, y + 38, s2, "t-cap")

a.add('<rect class="island" x="674" y="30" width="262" height="320" rx="12"/>')
a.text(694, 54, "CANE", "t-island", "start")
a.box(694, 68, 222, 62, "ESP32-C3 SuperMini", "all the hardware")
for i, line in enumerate(["ultrasonic → distance beeps", "ADXL345 → fall → siren → SOS", "joystick → app commands", "speaker + OLED", "beeps work with no network"]):
    a.text(805, 162 + i * 22, line, "t-note")

arrow(286, 264, 380, 264, both=True)
a.text(333, 244, "HTTPS +", "t-cap")
a.text(333, 257, "WebSocket", "t-cap")
arrow(674, 200, 580, 200, both=True)
a.text(627, 168, "WiFi", "t-cap")
a.text(627, 181, "WebSocket", "t-cap")
arrow(480, 350, 480, 396)
a.box(380, 400, 200, 56, "Dashboard", "judges · caregiver")
arrow(155, 332, 155, 396)
a.box(44, 400, 222, 56, "Caregiver's phone", "SMS + Google Maps link")
a.text(940, 392, "dashed line = the phone's WiFi hotspot", "t-cap", "end")
arch_svg = a.svg(960, 470, "Architecture: glasses and phone on one side, the cane on the other, joined only through the backend")

# ============================================================================== page
PAGE = r"""<title>Smart Eyes Wiring</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Barlow+Semi+Condensed:wght@500;600;700&family=Source+Sans+3:wght@400;600&family=JetBrains+Mono:wght@500&display=swap">
<style>
/* Layout: a datasheet. One reading column; diagrams sit in full-width scroll boxes. */
:root {
  --bg: #f4f6f8; --panel: #ffffff; --fg: #18212c; --muted: #5a6677; --line: #d3dae3; --accent: #1d5bd0;
  --pcb: #e6ecf6; --pcb-line: #6f86ab; --island: #f9fbfc;
  --w5v: #cf3434; --w33: #d9831a; --wgnd: #4b5666; --wsig: #1d6be0; --wi2c: #118a58; --wana: #8a43d4; --wspk: #b0368f;
  --display: "Barlow Semi Condensed", "Arial Narrow", system-ui, sans-serif;
  --body: "Source Sans 3", system-ui, -apple-system, "Segoe UI", sans-serif;
  --mono: "JetBrains Mono", ui-monospace, Consolas, monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg: #0f141a; --panel: #161d26; --fg: #e4e9ef; --muted: #95a2b3; --line: #2b3542; --accent: #73a3ff;
  --pcb: #151f30; --pcb-line: #4d6890; --island: #131a22;
  --w5v: #ff6b6b; --w33: #ffad4a; --wgnd: #a3aebd; --wsig: #62a6ff; --wi2c: #3ccb8c; --wana: #bb8cff; --wspk: #f07acb; color-scheme: dark; } }
:root[data-theme="dark"] {
  --bg: #0f141a; --panel: #161d26; --fg: #e4e9ef; --muted: #95a2b3; --line: #2b3542; --accent: #73a3ff;
  --pcb: #151f30; --pcb-line: #4d6890; --island: #131a22;
  --w5v: #ff6b6b; --w33: #ffad4a; --wgnd: #a3aebd; --wsig: #62a6ff; --wi2c: #3ccb8c; --wana: #bb8cff; --wspk: #f07acb; color-scheme: dark; }

body { background: var(--bg); color: var(--fg); font: 17px/1.55 var(--body);
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
.wrap { max-width: 1020px; margin: 0 auto; padding-block: 40px 72px; display: grid; gap: 44px; }
.prose { max-width: 68ch; }
h1, h2, h3 { font-family: var(--display); line-height: 1.1; text-wrap: balance; margin: 0; }
h1 { font-size: clamp(34px, 6vw, 52px); font-weight: 700; letter-spacing: -0.01em; }
h2 { font-size: 30px; font-weight: 600; }
h3 { font-size: 21px; font-weight: 600; }
p { margin: 0; }
section { display: grid; gap: 16px; min-width: 0; }
.eyebrow { font: 500 12px/1 var(--mono); letter-spacing: .12em; text-transform: uppercase; color: var(--muted); }
.lede { font-size: 19px; color: var(--muted); }
.fig { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; overflow-x: auto; padding: 12px; }
.fig svg { display: block; width: 100%; height: auto; }
.legend { display: flex; flex-wrap: wrap; gap: 8px 18px; font: 500 13px var(--mono); color: var(--muted); }
.legend span { display: inline-flex; align-items: center; gap: 7px; }
.legend i { width: 22px; height: 3px; border-radius: 2px; display: inline-block; }
.tablewrap { overflow-x: auto; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
table { border-collapse: collapse; width: 100%; font-size: 15px; min-width: 640px; }
th, td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { font: 600 13px var(--body); color: var(--muted); text-transform: uppercase; letter-spacing: .06em; }
tr:last-child td { border-bottom: 0; }
td code, p code, li code { font: 500 13.5px var(--mono); background: color-mix(in srgb, var(--accent) 10%, transparent); padding: 1px 5px; border-radius: 4px; }
td.pinc { font: 500 14px var(--mono); white-space: nowrap; }
.warn { background: var(--panel); border: 1px solid var(--line); border-left: 4px solid var(--w5v); border-radius: 4px 10px 10px 4px; padding: 16px 20px; display: grid; gap: 8px; }
ul.notes { margin: 0; padding-left: 1.2em; display: grid; gap: 8px; max-width: 72ch; }
.two { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; }
.two > div { min-width: 0; }
.muted { color: var(--muted); }

.board { fill: var(--pcb); stroke: var(--pcb-line); stroke-width: 2; }
.comp { fill: var(--panel); stroke: var(--fg); stroke-width: 1.5; }
.island { fill: var(--island); stroke: var(--line); stroke-width: 1.5; }
.hub { fill: color-mix(in srgb, var(--accent) 8%, var(--panel)); stroke: var(--accent); stroke-width: 1.8; }
.inner { fill: var(--panel); stroke: var(--line); stroke-width: 1.2; }
.wifi { fill: none; stroke: var(--muted); stroke-width: 1.2; stroke-dasharray: 5 6; }
.usb { fill: var(--muted); }
.btn { fill: var(--panel); stroke: var(--muted); stroke-width: 1.2; }
.chip { fill: var(--fg); }
.ant { fill: none; stroke: var(--muted); stroke-width: 1.2; stroke-dasharray: 3 3; }
.hole { fill: var(--panel); stroke: #c9a227; stroke-width: 3; }
.t-chip { font: 500 10px var(--mono); fill: var(--muted); text-anchor: middle; }
.t-chipw { font: 600 10px var(--mono); fill: var(--bg); text-anchor: middle; }
.t-silk { font: 600 14px var(--mono); }
.t-free { font: italic 400 11.5px var(--body); fill: var(--muted); }
.npn { fill: var(--panel); stroke: var(--fg); stroke-width: 1.5; }
.arrowhead { fill: var(--wgnd); }
.pin { fill: var(--fg); }
.w { fill: none; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round; }
.w.p5 { stroke: var(--w5v); } .w.p33 { stroke: var(--w33); } .w.gnd { stroke: var(--wgnd); }
.w.sig { stroke: var(--wsig); } .w.i2c { stroke: var(--wi2c); } .w.ana { stroke: var(--wana); } .w.spk { stroke: var(--wspk); }
.w.p5sig { stroke: var(--w5v); stroke-dasharray: 6 4; }
.dot.i2c { fill: var(--wi2c); } .dot.sig { fill: var(--wsig); }
.tag { fill: var(--panel); stroke-width: 2; }
.tag.p5 { stroke: var(--w5v); } .tag.p33 { stroke: var(--w33); } .tag.gnd { stroke: var(--wgnd); }
.flow { stroke: var(--muted); stroke-width: 2; }
.ahead { fill: var(--muted); }
svg text { fill: var(--fg); }
.t-title { font: 600 15px var(--body); }
.t-sub { font: 400 12.5px var(--body); fill: var(--muted); }
.t-cap { font: 400 12px var(--body); fill: var(--muted); }
.t-note { font: 400 13px var(--body); fill: var(--muted); text-anchor: middle; }
.t-pin { font: 500 12px var(--mono); }
.t-board { font: 700 18px var(--display); }
.t-boardsub { font: 400 12px var(--body); fill: var(--muted); }
.t-island { font: 500 11px var(--mono); letter-spacing: .12em; fill: var(--muted); }
.t-tag { font: 600 11px var(--mono); }
.t-tag.p5 { fill: var(--w5v); } .t-tag.p33 { fill: var(--w33); } .t-tag.gnd { fill: var(--wgnd); }
</style>

<div class="wrap">
  <header style="display:grid;gap:14px">
    <div class="eyebrow">Smart Eyes · hardware plan v3</div>
    <h1>Smart Eyes Wiring</h1>
    <p class="lede prose">All the hardware lives on the cane, on one ESP32-C3 SuperMini. The glasses and the phone handle seeing and speaking. The two halves only talk through the backend, over WiFi.</p>
  </header>

  <section>
    <div class="fig">__ARCH__</div>
    <div class="tablewrap"><table>
      <thead><tr><th>Part</th><th>Job</th><th>Works without internet?</th></tr></thead>
      <tbody>
        <tr><td>Cane: ultrasonic</td><td>Beeps faster and higher as obstacles get closer; solid tone under 30 cm</td><td>Yes</td></tr>
        <tr><td>Cane: ADXL345</td><td>Cane falls and lies still → locator siren → 5 s to press the joystick → SOS with GPS. Picking the cane up or any joystick move cancels it.</td><td>Countdown yes, SOS needs WiFi</td></tr>
        <tr><td>Phone: YOLO</td><td>Runs on every frame and announces what it sees every 5–10 s. Warns instantly if something is close in front.</td><td>Yes</td></tr>
        <tr><td>Phone: Gemini</td><td>Only when the joystick asks: describe, read text, answer a question</td><td>No</td></tr>
        <tr><td>Phone: SOS</td><td>Texts the caregiver with a Google Maps link and shows a red alert on the dashboard</td><td>Needs cell signal</td></tr>
      </tbody>
    </table></div>
  </section>

  <section>
    <h3>Joystick on the handle</h3>
    <div class="tablewrap"><table>
      <thead><tr><th>Move</th><th>Does</th><th>Cane beeps</th></tr></thead>
      <tbody>
        <tr><td>Press</td><td>Describe what's in front (Gemini)</td><td>1 short</td></tr>
        <tr><td>Up</td><td>Read text (sign, menu, label)</td><td>2 short</td></tr>
        <tr><td>Down</td><td>Ask a question by voice, e.g. "where's my backpack?"</td><td>3 short</td></tr>
        <tr><td>Left</td><td>Repeat the last thing said</td><td>1 short</td></tr>
        <tr><td>Right</td><td>Turn YOLO announcements on or off</td><td>1 short</td></tr>
        <tr><td>Hold press 3 s</td><td>SOS right away</td><td>Siren</td></tr>
        <tr><td>Any move during a countdown or siren</td><td>Cancel</td><td>1 long, low</td></tr>
      </tbody>
    </table></div>
    <p class="muted prose">If the cane can't reach the server, a press gets a low double beep instead, so the user knows it didn't go through.</p>
  </section>

  <section>
    <div class="eyebrow">The cane</div>
    <h2>ESP32-C3 SuperMini wiring</h2>
    <div class="legend">
      <span><i style="background:var(--w5v)"></i>5 V</span><span><i style="background:var(--w33)"></i>3.3 V</span>
      <span><i style="background:var(--wgnd)"></i>GND</span><span><i style="background:var(--wsig)"></i>digital</span>
      <span><i style="background:var(--wana)"></i>analog</span><span><i style="background:var(--wi2c)"></i>I2C</span>
      <span><i style="background:var(--wspk)"></i>speaker drive</span>
    </div>
    <div class="fig">__CANE__</div>
    <p class="muted prose">The board is drawn as it looks from the top with the USB-C port up, and the pin numbers match what is printed on it. A rounded tag such as <code>5V</code>, <code>3.3V</code> or <code>GND</code> means "connect to that pin on the right header" (printed <code>5V</code>, <code>3.3</code>, <code>G</code>). Every GND is joined together.</p>

    <div class="warn prose">
      <strong>Two rules that protect the board</strong>
      <p>1. Never put 5 V into a GPIO. The HC-SR04's ECHO pin outputs 5 V, so it goes through the 1 kΩ + 2 kΩ divider. Power the joystick, ADXL345 and OLED from <code>3.3</code>, not <code>5V</code>.</p>
      <p>2. Never wire the speaker straight to a GPIO. A 4–8 Ω speaker would pull about 400 mA and burn the pin, which can supply about 40 mA. The transistor does the heavy lifting.</p>
    </div>

    <div class="two">
      <div class="tablewrap"><table style="min-width:0">
        <thead><tr><th colspan="2">Left header, top to bottom</th></tr></thead>
        <tbody>
          <tr><td class="pinc">5</td><td class="muted">free</td></tr>
          <tr><td class="pinc">6</td><td>SDA of ADXL345 + OLED</td></tr>
          <tr><td class="pinc">7</td><td>SCL of ADXL345 + OLED</td></tr>
          <tr><td class="pinc">8</td><td class="muted">blue LED, leave free</td></tr>
          <tr><td class="pinc">9</td><td class="muted">BOOT button, leave free</td></tr>
          <tr><td class="pinc">10</td><td>free</td></tr>
          <tr><td class="pinc">20</td><td>HC-SR04 TRIG</td></tr>
          <tr><td class="pinc">21</td><td>HC-SR04 ECHO through 1 kΩ, plus 2 kΩ from 21 to GND</td></tr>
        </tbody>
      </table></div>
      <div class="tablewrap"><table style="min-width:0">
        <thead><tr><th colspan="2">Right header, top to bottom</th></tr></thead>
        <tbody>
          <tr><td class="pinc">5V</td><td>HC-SR04 VCC, speaker (via 22 Ω), 100 µF +</td></tr>
          <tr><td class="pinc">G</td><td>every GND</td></tr>
          <tr><td class="pinc">3.3</td><td>ADXL345 VCC + CS, OLED, joystick “+5V”</td></tr>
          <tr><td class="pinc">4</td><td>speaker: 1 kΩ → NPN base</td></tr>
          <tr><td class="pinc">3</td><td>joystick SW</td></tr>
          <tr><td class="pinc">2</td><td class="muted">boot pin, leave free</td></tr>
          <tr><td class="pinc">1</td><td>joystick VRy</td></tr>
          <tr><td class="pinc">0</td><td>joystick VRx</td></tr>
        </tbody>
      </table></div>
    </div>
    <div class="tablewrap"><table>
      <thead><tr><th>Part</th><th>Where it goes on the cane</th></tr></thead>
      <tbody>
        <tr><td>HC-SR04</td><td>On the shaft at about knee height, facing forward</td></tr>
        <tr><td>Joystick</td><td>Under your thumb on the handle. If directions come out rotated, swap them in <code>pollJoystick()</code>.</td></tr>
        <tr><td>ADXL345</td><td>Y arrow along the shaft (or change <code>CANE_AXIS</code>)</td></tr>
        <tr><td>OLED</td><td>On the handle, facing up, so judges can read it</td></tr>
        <tr><td>Board, speaker, power bank</td><td>Taped near the handle, with the speaker facing up or out</td></tr>
      </tbody>
    </table></div>
  </section>

  <section>
    <h3>Parts beyond what you have</h3>
    <div class="two">
      <div><ul class="notes">
        <li><strong>Resistors:</strong> 1 kΩ ×2, 2 kΩ ×1 (or two 1 kΩ in series), 22 Ω ×1. A 10 kΩ + 20 kΩ pair also works for the divider.</li>
        <li><strong>NPN transistor:</strong> 2N2222, S8050 or PN2222.</li>
        <li><strong>Diode:</strong> 1N4148 or 1N4007.</li>
        <li><strong>Capacitor:</strong> 100 µF (any voltage of 10 V or more).</li>
      </ul></div>
      <div><ul class="notes">
        <li><strong>Easier speaker option:</strong> a PAM8403 amp module (about $1). Wire GPIO4→L-IN, G→GND, 5V→VCC, speaker→L+/L−. Then skip the transistor parts.</li>
        <li><strong>No resistors for the divider?</strong> Some newer HC-SR04 boards (HC-SR04P, RCWL-9610) run on 3.3 V. Power them from 3.3 and connect ECHO directly.</li>
        <li><strong>Power:</strong> a USB power bank into the SuperMini's USB-C. Its 5V pin then feeds the HC-SR04 and the speaker.</li>
      </ul></div>
    </div>
  </section>

  <section>
    <h3>Bring-up order</h3>
    <ul class="notes">
      <li>Flash with <strong>USB CDC On Boot: Enabled</strong> and open the Serial Monitor at 115200.</li>
      <li>Wire and test one part at a time: speaker, then ultrasonic, joystick, ADXL345, OLED. Use <code>firmware/hardware_test</code>: type one letter in the Serial Monitor to test each part.</li>
      <li>Set <code>WIFI_SSID</code>, <code>WIFI_PASS</code> and <code>BACKEND_HOST</code>. The blue on-board LED lights when the cane reaches the server.</li>
      <li>Fall test: lay the cane flat on the floor with a thump. The siren should start after about 2 s, then the SOS goes out 5 s later. Pressing or moving the joystick, or picking the cane up, cancels it.</li>
    </ul>
  </section>
</div>
"""

page = PAGE.replace("__ARCH__", arch_svg).replace("__CANE__", cane_svg)
with open(out_path, "w", encoding="utf-8") as f:
    f.write(page)
print("wrote", len(page))
