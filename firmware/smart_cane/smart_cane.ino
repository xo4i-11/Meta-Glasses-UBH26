/*
 * Smart Eyes: SMART CANE firmware, ESP32-C3 SuperMini.
 *
 * Everything on the cane:
 *   HC-SR04 ultrasonic -> distance beeps (faster + higher = closer)       works with NO network
 *   ADXL345 -> cane fell and lies still -> siren + 5 s to press -> SOS    countdown local, SOS needs WiFi
 *   Joystick -> describe / read / ask / repeat / auto on-off / SOS        sent to the phone app via backend
 *   Speaker (through a transistor) + OLED status screen
 *
 * Network: joins the phone's hotspot, opens a WebSocket to the backend (/ws/device?role=cane).
 * The backend relays joystick presses to the phone app and phone commands (tones, "find my cane") back.
 *
 * Arduino IDE setup:
 *   Board:  "ESP32C3 Dev Module" (Boards Manager: esp32 by Espressif)
 *           Tools > USB CDC On Boot: "Enabled"  (otherwise Serial Monitor shows nothing)
 *   Libs:   "WebSockets" by Markus Sattler, "ArduinoJson" by Benoit Blanchon (v7),
 *           "Adafruit SSD1306" + "Adafruit GFX Library"
 *
 * Wiring (see docs/wiring.html). SuperMini top view, USB-C up:
 *   LEFT header  (5, 6, 7, 8, 9, 10, 20, 21):  6 SDA · 7 SCL · 20 TRIG · 21 ECHO  (5, 10 free)
 *   RIGHT header (5V, G, 3.3, 4, 3, 2, 1, 0):  4 speaker · 3 joystick SW · 1 VRy · 0 VRx
 *
 *   HC-SR04   VCC->5V  GND->G  TRIG->20  ECHO->1k->21, and 21->2k->G      (ECHO is 5 V: divider!)
 *   Joystick  "+5V" pin->3.3 (NOT 5V)  GND->G  VRx->0  VRy->1  SW->3
 *   ADXL345   VCC->3.3  GND->G  CS->3.3  SDO->G  SDA->6  SCL->7   (Y arrow along the shaft)
 *   OLED      VCC->3.3  GND->G  SDA->6  SCL->7
 *   Speaker   4->1k->NPN base; emitter->G; collector->speaker(-); speaker(+)->22R->5V
 *             diode across speaker (stripe to 5V side); 100uF across 5V/G near the speaker
 *   Leave 2, 8 (blue LED), 9 (BOOT) and 5 free.
 */

#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ============================================================================= SETTINGS
const char *WIFI_SSID = "YOUR_PHONE_HOTSPOT";
const char *WIFI_PASS = "your-password";
const char *BACKEND_HOST = "192.168.43.100";  // laptop IP on the hotspot, or your domain
const uint16_t BACKEND_PORT = 8000;           // 80 on Vultr (docker -p 80:8000), 443 with SSL
const bool BACKEND_SSL = false;               // true if the backend is behind https
const char *APP_TOKEN = "";                   // same as backend APP_TOKEN (empty if unset)

#define USE_OLED 1
#define USE_IMU 1

// ============================================================================= PINS
// Right header: 4 speaker, 3 joystick button, 1 joystick Y, 0 joystick X
const int PIN_SPK = 4, PIN_SW = 3, PIN_JOY_Y = 1, PIN_JOY_X = 0;
// Left header: 6 SDA, 7 SCL, 20 TRIG, 21 ECHO (through the 1k/2k divider)
const int PIN_SDA = 6, PIN_SCL = 7, PIN_TRIG = 20, PIN_ECHO = 21;
const int PIN_LED = 8;  // on-board blue LED, active LOW
const uint8_t ADXL = 0x53;

// ============================================================================= TUNING
int alertCm = 60;            // start beeping below this (150 = early warning when walking)
const int SOLID_CM = 15;     // continuous tone below this
const unsigned long SOS_HOLD_MS = 3000;
const unsigned long COUNTDOWN_MS = 5000;  // fall -> locator siren -> SOS if nobody presses the joystick
// Fall = the cane lies still, tilted far from its upright direction.
// UPRIGHT_* = gravity measured with this cane held as when walking (re-measure if the board is re-mounted).
// Measured 2026-11-08: upright (-0.06,-0.73,+0.67), lying on the floor (-0.06,-0.14,+0.97) -> 41 deg apart;
// walking stayed within ~20 deg, so 28 deg splits them.
const float UPRIGHT_X = -0.06f, UPRIGHT_Y = -0.73f, UPRIGHT_Z = 0.67f;
const float FLAT_TILT_DEG = 28.0f;      // this far from upright counts as lying down
const unsigned long FLAT_MS = 2000;     // ...still for this long -> siren + countdown

// ============================================================================= STATE
WebSocketsClient ws;
bool wsUp = false;
bool autoMode = true;       // mirrors the phone's auto announcements (shown on OLED)
bool pendingSos = false;    // SOS waiting for the network
String pendingReason = "";
long distanceCm = -1;
String lastAction = "starting";

// Declared before any function: the Arduino IDE inserts prototypes above the first function.
enum Mode { NORMAL, IMPACT, COUNTDOWN, SOS_SENT, FIND };
Mode mode = NORMAL;
unsigned long modeSince = 0;

#if USE_OLED
Adafruit_SSD1306 oled(128, 64, &Wire, -1);
bool oledOk = false;
#endif

// ============================================================================= SOUND
// Priority: alarms (fall countdown, SOS, find) > phone tones > distance beeps.
unsigned currentFreq = 0;
void spk(unsigned f) {
  if (f == currentFreq) return;
  if (f) tone(PIN_SPK, f); else noTone(PIN_SPK);
  currentFreq = f;
}

struct Pattern {
  unsigned freq = 0, freq2 = 0;  // freq2 != 0 alternates pitches (siren)
  int pulses = 0;
  unsigned onMs = 0, offMs = 0;
  bool on = false;
  unsigned long next = 0;
} pat;

void play(unsigned freq, int pulses, unsigned onMs, unsigned offMs, unsigned freq2 = 0) {
  pat.freq = freq; pat.freq2 = freq2; pat.pulses = pulses; pat.onMs = onMs; pat.offMs = offMs;
  pat.on = false; pat.next = millis();
}

void setMode(Mode m) { mode = m; modeSince = millis(); }

unsigned long distNext = 0;
bool distOn = false;

void runSound(unsigned long now) {
  // 1) alarm modes own the speaker
  if (mode == COUNTDOWN) {           // fall: fast two-tone "find my cane" siren until pressed or SOS
    unsigned long t = now - modeSince;
    spk((t / 150) % 2 ? 1400 : 2100);
    return;
  }
  if (mode == SOS_SENT || mode == FIND) {  // locator siren so people can find the cane / user
    unsigned long t = now - modeSince;
    spk((t % 2000) < 900 ? ((t / 225) % 2 ? 1400 : 2100) : 0);
    return;
  }
  // 2) one-shot patterns from the phone or confirmations
  if (pat.pulses > 0) {
    if ((long)(now - pat.next) >= 0) {
      if (!pat.on) {
        spk(pat.freq2 && (pat.pulses % 2) ? pat.freq2 : pat.freq);
        pat.on = true;
        pat.next = now + pat.onMs;
      } else {
        spk(0);
        pat.on = false;
        pat.pulses--;
        pat.next = now + pat.offMs;
      }
    }
    return;
  }
  // 3) distance: closer = higher + faster
  if (distanceCm <= 0 || distanceCm >= alertCm) { spk(0); distOn = false; return; }
  unsigned f = map(constrain(distanceCm, SOLID_CM, alertCm), SOLID_CM, alertCm, 2000, 700);
  if (distanceCm <= SOLID_CM) { spk(f); return; }
  unsigned long gap = map(distanceCm, SOLID_CM, alertCm, 80, 700);
  if ((long)(now - distNext) >= 0) {
    distOn = !distOn;
    spk(distOn ? f : 0);
    distNext = now + (distOn ? 50 : gap);
  }
}

// ============================================================================= NETWORK

void sendJson(JsonDocument &doc) {
  if (!wsUp) return;
  String s;
  serializeJson(doc, s);
  ws.sendTXT(s);
}

void sendSimple(const char *t, const char *key = nullptr, const char *val = nullptr) {
  JsonDocument d;
  d["t"] = t;
  if (key) d[key] = val;
  sendJson(d);
}

void sendSos(const char *reason) {
  pendingSos = true;  // retried on reconnect until it goes out
  pendingReason = reason;
  if (wsUp) {
    sendSimple("sos", "reason", reason);
    pendingSos = false;
  }
}

void cancelAlarm();

void onWs(WStype_t type, uint8_t *payload, size_t len) {
  if (type == WStype_CONNECTED) {
    wsUp = true;
    Serial.println("[ws] connected");
    if (pendingSos) { sendSimple("sos", "reason", pendingReason.c_str()); pendingSos = false; }
  } else if (type == WStype_DISCONNECTED) {
    if (wsUp) Serial.println("[ws] disconnected");
    wsUp = false;
  } else if (type == WStype_TEXT) {
    JsonDocument d;
    if (deserializeJson(d, payload, len)) return;
    const char *t = d["t"] | "";
    if (!strcmp(t, "tone")) play(d["f"] | 1500, d["n"] | 1, d["on"] | 80, d["off"] | 60);
    else if (!strcmp(t, "find")) { setMode(FIND); lastAction = "find my cane"; }
    else if (!strcmp(t, "cancel")) { if (mode == COUNTDOWN || mode == SOS_SENT || mode == FIND) cancelAlarm(); }  // app's Cancel SOS
    else if (!strcmp(t, "auto")) autoMode = d["on"] | true;
    else if (!strcmp(t, "cfg")) alertCm = constrain((int)(d["th"] | alertCm), 30, 400);
  }
}

void setupNet() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);  // C3 SuperMini antenna quirk: lower power connects far more reliably
  WiFi.setAutoReconnect(true);
  String path = String("/ws/device?role=cane&token=") + APP_TOKEN;
  if (BACKEND_SSL) ws.beginSSL(BACKEND_HOST, BACKEND_PORT, path.c_str());
  else ws.begin(BACKEND_HOST, BACKEND_PORT, path.c_str());
  ws.onEvent(onWs);
  ws.setReconnectInterval(2000);
  ws.enableHeartbeat(15000, 3000, 2);
}

// ============================================================================= SENSORS

long readDistanceCm() {
  digitalWrite(PIN_TRIG, LOW); delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  unsigned long us = pulseIn(PIN_ECHO, HIGH, 25000UL);  // ~4 m max
  return us == 0 ? -1 : (long)(us / 58UL);
}

long median3(long a, long b, long c) {
  if ((a <= b && b <= c) || (c <= b && b <= a)) return b;
  if ((b <= a && a <= c) || (c <= a && a <= b)) return a;
  return c;
}

#if USE_IMU
bool imuOk = false;
float ax, ay, az, gMag;

void adxlWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(ADXL); Wire.write(reg); Wire.write(val); Wire.endTransmission();
}

bool adxlInit() {
  Wire.beginTransmission(ADXL); Wire.write(0x00); Wire.endTransmission(false);
  if (Wire.requestFrom(ADXL, (uint8_t)1) != 1 || Wire.read() != 0xE5) return false;  // DEVID
  adxlWrite(0x31, 0x0B);  // full resolution, ±16 g
  adxlWrite(0x2C, 0x0A);  // 100 Hz
  adxlWrite(0x2D, 0x08);  // measure
  return true;
}

void readImu() {
  Wire.beginTransmission(ADXL); Wire.write(0x32); Wire.endTransmission(false);
  if (Wire.requestFrom(ADXL, (uint8_t)6) != 6) return;
  int16_t x = Wire.read() | (Wire.read() << 8);
  int16_t y = Wire.read() | (Wire.read() << 8);
  int16_t z = Wire.read() | (Wire.read() << 8);
  ax = x * 0.0039f; ay = y * 0.0039f; az = z * 0.0039f;  // 3.9 mg/LSB
  gMag = sqrtf(ax * ax + ay * ay + az * az);
}

// Lying still more than FLAT_TILT_DEG from upright for 2 s -> siren + 5 s to press -> SOS.
// ponytail: tight margin (walking ~20, floor ~41 deg); if it false-alarms or misses, re-measure UPRIGHT_*.
bool armed = true;                  // after an alarm, wait until it is picked up again
unsigned long lastMoveAt = 0, flatSince = 0, movingSince = 0;

float tiltDeg() {
  float r = sqrtf(UPRIGHT_X * UPRIGHT_X + UPRIGHT_Y * UPRIGHT_Y + UPRIGHT_Z * UPRIGHT_Z);
  if (gMag < 0.01f) return 0;
  float c = (ax * UPRIGHT_X + ay * UPRIGHT_Y + az * UPRIGHT_Z) / (r * gMag);
  return acosf(constrain(c, -1.0f, 1.0f)) * 57.2958f;
}

void runFallLogic(unsigned long now) {
  if (!imuOk) return;
  readImu();
  bool still = fabsf(gMag - 1.0f) < 0.12f;
  if (!still) lastMoveAt = now;
  bool inUse = now - lastMoveAt < 1000;  // moved within the last second: someone is carrying it
  float tilt = tiltDeg();
  if (tilt < 15.0f) armed = true;  // back near upright

  if (mode == NORMAL && armed) {
    if (!inUse && tilt > FLAT_TILT_DEG) {
      if (!flatSince) flatSince = now;
      if (now - flatSince > FLAT_MS) {  // lying flat & still -> it fell
        armed = false; flatSince = 0;
        setMode(COUNTDOWN);
        lastAction = "FALL? press to cancel";
        sendSimple("fall");
      }
    } else flatSince = 0;
  }
  // Picked back up during countdown / after the SOS / siren (moving for 0.7 s) -> user is fine
  if (mode == COUNTDOWN || mode == SOS_SENT || mode == FIND) {
    if (!still) { if (!movingSince) movingSince = now; }
    else if (now - lastMoveAt > 200) movingSince = 0;
    if (movingSince && now - movingSince > 700) {
      movingSince = 0;
      cancelAlarm();
      lastAction = "picked up";
    }
  }
}
#endif

// ============================================================================= JOYSTICK

void cancelAlarm();

int joyDir = 0;
bool swDown = false, sosHeld = false;
unsigned long swAt = 0, swChanged = 0;

void button(const char *b, const char *label, int beeps) {
  sendSimple("btn", "b", b);
  lastAction = wsUp ? label : "offline!";
  if (wsUp) play(1500, beeps, 40, 60);
  else play(400, 2, 150, 80);  // low double beep = no connection
}

void pollJoystick(unsigned long now) {
  int x = analogRead(PIN_JOY_X), y = analogRead(PIN_JOY_Y);  // 0..4095, centre ~2048
  int dir = 0;
  if (y < 700) dir = 1; else if (y > 3400) dir = 2; else if (x < 700) dir = 3; else if (x > 3400) dir = 4;
  // If directions feel rotated on your handle, swap the cases below.
  if (dir != joyDir) {
    joyDir = dir;
    if (mode == COUNTDOWN || mode == SOS_SENT || mode == FIND) {
      if (dir) cancelAlarm();
      return;
    }
    if (dir == 1) button("read", "read text", 2);
    if (dir == 2) button("ask", "ask", 3);
    if (dir == 3) button("repeat", "repeat", 1);
    if (dir == 4) button("auto", "auto on/off", 1);
  }

  bool down = digitalRead(PIN_SW) == LOW;
  if (down != swDown && now - swChanged > 30) {
    swChanged = now;
    swDown = down;
    if (down) { swAt = now; sosHeld = false; }
    else if (!sosHeld) {
      if (mode == COUNTDOWN || mode == SOS_SENT || mode == FIND) cancelAlarm();
      else button("describe", "describe", 1);
    }
  }
  if (swDown && !sosHeld && now - swAt > SOS_HOLD_MS && mode == NORMAL) {
    sosHeld = true;
    sendSos("SOS button held");
    setMode(SOS_SENT);
    lastAction = "SOS SENT";
  }
}

void cancelAlarm() {
  if (mode == COUNTDOWN || mode == SOS_SENT) sendSimple("cancel");  // phone tells the caregiver "all clear"
  pendingSos = false;  // cancelled before it could go out
  setMode(NORMAL);
  lastAction = "cancelled";
  play(900, 1, 200, 50);
}

// ============================================================================= DISPLAY

void draw() {
#if USE_OLED
  if (!oledOk) return;
  oled.clearDisplay();
  oled.setTextColor(SSD1306_WHITE);
  oled.setTextSize(1);
  oled.setCursor(0, 0);
  oled.print(WiFi.status() != WL_CONNECTED ? "no WiFi" : wsUp ? "online" : "no server");
  oled.setCursor(74, 0);
  oled.print(autoMode ? "auto ON" : "auto off");
  oled.setTextSize(3);
  oled.setCursor(0, 16);
  if (mode == COUNTDOWN) { oled.print("FALL "); oled.print((COUNTDOWN_MS - min(COUNTDOWN_MS, millis() - modeSince)) / 1000 + 1); }
  else if (mode == SOS_SENT) oled.print("SOS");
  else if (distanceCm < 0) oled.print("---");
  else { oled.print(distanceCm); oled.setTextSize(1); oled.print(" cm"); }
  oled.setTextSize(1);
  oled.setCursor(0, 54);
  oled.print(lastAction);
  oled.display();
#endif
}

// ============================================================================= MAIN

long distBuf[3] = {-1, -1, -1};
int distIdx = 0;
unsigned long lastDist = 0, lastTele = 0, lastDraw = 0, lastImu = 0;

void setup() {
  Serial.begin(115200);
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_SW, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
  analogReadResolution(12);

  Wire.begin(PIN_SDA, PIN_SCL);
#if USE_OLED
  oledOk = oled.begin(SSD1306_SWITCHCAPVCC, 0x3C);
  if (!oledOk) Serial.println("OLED not found at 0x3C (try 0x3D)");
#endif
#if USE_IMU
  imuOk = adxlInit();
  if (!imuOk) Serial.println("ADXL345 not found: check CS->3.3V, SDO->GND, SDA/SCL");
#endif

  setupNet();
  play(1200, 2, 80, 80);  // "I'm on"
  lastAction = "ready";
  Serial.println("Smart cane ready");
}

void logWifi() {  // prints WiFi changes over USB so you can see why the cane is offline
  static int last = -1;
  int st = WiFi.status();
  if (st == last) return;
  last = st;
  if (st == WL_CONNECTED)
    Serial.printf("[wifi] connected to %s, ip %s, backend %s:%u\n", WIFI_SSID, WiFi.localIP().toString().c_str(), BACKEND_HOST, BACKEND_PORT);
  else
    Serial.printf("[wifi] status %d (1=network not found, 4=wrong password/rejected, 6=disconnected), mac %s\n", st, WiFi.macAddress().c_str());
}

void loop() {
  unsigned long now = millis();
  ws.loop();
  logWifi();

  if (now - lastDist >= 60) {
    lastDist = now;
    distBuf[distIdx] = readDistanceCm();
    distIdx = (distIdx + 1) % 3;
    distanceCm = median3(distBuf[0], distBuf[1], distBuf[2]);
  }

#if USE_IMU
  if (now - lastImu >= 10) { lastImu = now; runFallLogic(now); }
  if (mode == COUNTDOWN && now - modeSince >= COUNTDOWN_MS) {
    sendSos("fall: cane down, no response");
    setMode(SOS_SENT);
    lastAction = "SOS SENT";
  }
#endif
  if (mode == SOS_SENT && now - modeSince > 60000) setMode(NORMAL);  // stop the siren after a minute
  if (mode == FIND && now - modeSince > 20000) setMode(NORMAL);

  pollJoystick(now);
  runSound(now);

  if (now - lastTele >= 250) {
    lastTele = now;
    JsonDocument d;
    d["t"] = "tele";
    d["d"] = distanceCm;
    d["auto"] = autoMode;
    sendJson(d);
    digitalWrite(PIN_LED, wsUp ? LOW : HIGH);  // on-board LED lit = connected to backend
  }
  if (now - lastDraw >= 250) { lastDraw = now; draw(); }
}
