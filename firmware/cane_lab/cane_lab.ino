/*
 * Smart Eyes: CANE LAB firmware. Pairs with tools/cane-lab.html (open it in Chrome or Edge).
 *
 * Streams every sensor as one JSON line ~20x per second over USB serial, runs the SAME joystick
 * logic as smart_cane.ino (and reports which app command each move would send), and plays a
 * proximity alarm from the ultrasonic. Same pins as smart_cane.ino.
 *
 * Board: "ESP32C3 Dev Module", Tools > USB CDC On Boot: Enabled.
 * Libs:  Adafruit SSD1306 + Adafruit GFX.
 *
 * Commands from the page (newline terminated):
 *   tone <hz> <ms>   sweep   beep <n>   led   oled <text>   scan
 *   alarm <0|1>      alarmcm <cm>        (proximity alarm on/off, start distance)
 *
 * Extra lines it sends:
 *   {"btn":"describe|read|ask|repeat|auto|sos"}   when the joystick does something
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// Right header: 4 speaker, 3 joystick button, 1 joystick Y, 0 joystick X
const int PIN_SPK = 4, PIN_SW = 3, PIN_JOY_Y = 1, PIN_JOY_X = 0;
// Left header: 6 SDA, 7 SCL, 10 IR, 20 TRIG, 21 ECHO (through the 1k/2k divider)
const int PIN_SDA = 6, PIN_SCL = 7, PIN_IR = 10, PIN_TRIG = 20, PIN_ECHO = 21;
const int PIN_LED = 8;
const int SPARE[] = {2, 5, 9};  // "pin finder": pulled up, a button to GND reads 0
const uint8_t ADXL = 0x53;
const unsigned long SOS_HOLD_MS = 3000;

Adafruit_SSD1306 oled(128, 64, &Wire, -1);
bool oledOk = false, adxlOk = false;
String oledText = "", lastBtn = "";
String cmd = "";
unsigned long lastSample = 0, lastScan = 0, lastDraw = 0;
long distanceCm = -1;
int swNow = 1;

// ---- proximity alarm
bool alarmOn = true;
int alarmCm = 10;
const int SOLID_CM = 3;

// ============================================================================= sound
unsigned currentFreq = 0;
void spk(unsigned f) {
  if (f == currentFreq) return;
  if (f) tone(PIN_SPK, f); else noTone(PIN_SPK);
  currentFreq = f;
}

struct Pattern { unsigned freq = 0; int pulses = 0; unsigned onMs = 0, offMs = 0; bool on = false; unsigned long next = 0; } pat;
void play(unsigned f, int n, unsigned onMs, unsigned offMs) { pat.freq = f; pat.pulses = n; pat.onMs = onMs; pat.offMs = offMs; pat.on = false; pat.next = millis(); }

unsigned long alarmNext = 0;
bool alarmBeepOn = false;

void runSound(unsigned long now) {
  // 1) confirmation beeps win
  if (pat.pulses > 0) {
    if ((long)(now - pat.next) >= 0) {
      if (!pat.on) { spk(pat.freq); pat.on = true; pat.next = now + pat.onMs; }
      else { spk(0); pat.on = false; pat.pulses--; pat.next = now + pat.offMs; }
    }
    return;
  }
  // 2) proximity alarm: below alarmCm, faster + higher as it gets closer, solid when nearly touching
  if (!alarmOn || distanceCm <= 0 || distanceCm >= alarmCm) { spk(0); alarmBeepOn = false; return; }
  long d = constrain(distanceCm, SOLID_CM, alarmCm);
  unsigned f = map(d, SOLID_CM, alarmCm, 2600, 1200);
  if (distanceCm <= SOLID_CM) { spk(f); return; }
  unsigned long gap = map(d, SOLID_CM, alarmCm, 40, 450);
  if ((long)(now - alarmNext) >= 0) {
    alarmBeepOn = !alarmBeepOn;
    spk(alarmBeepOn ? f : 0);
    alarmNext = now + (alarmBeepOn ? 45 : gap);
  }
}

// ============================================================================= sensors
bool adxlInit() {
  Wire.beginTransmission(ADXL); Wire.write(0x00); Wire.endTransmission(false);
  if (Wire.requestFrom(ADXL, (uint8_t)1) != 1 || Wire.read() != 0xE5) return false;
  Wire.beginTransmission(ADXL); Wire.write(0x31); Wire.write(0x0B); Wire.endTransmission();
  Wire.beginTransmission(ADXL); Wire.write(0x2C); Wire.write(0x0A); Wire.endTransmission();
  Wire.beginTransmission(ADXL); Wire.write(0x2D); Wire.write(0x08); Wire.endTransmission();
  return true;
}

bool readAccel(float &x, float &y, float &z) {
  Wire.beginTransmission(ADXL); Wire.write(0x32); Wire.endTransmission(false);
  if (Wire.requestFrom(ADXL, (uint8_t)6) != 6) return false;
  int16_t rx = Wire.read() | (Wire.read() << 8);
  int16_t ry = Wire.read() | (Wire.read() << 8);
  int16_t rz = Wire.read() | (Wire.read() << 8);
  x = rx * 0.0039f; y = ry * 0.0039f; z = rz * 0.0039f;
  return true;
}

long readDistance() {
  digitalWrite(PIN_TRIG, LOW); delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  unsigned long us = pulseIn(PIN_ECHO, HIGH, 25000UL);
  return us == 0 ? -1 : (long)(us / 58UL);
}

long median3(long a, long b, long c) {
  if ((a <= b && b <= c) || (c <= b && b <= a)) return b;
  if ((b <= a && a <= c) || (c <= a && a <= b)) return a;
  return c;
}

void scanI2C() {
  Serial.print("{\"i2c\":[");
  bool first = true;
  for (uint8_t a = 1; a < 127; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) { if (!first) Serial.print(","); Serial.print(a); first = false; }
  }
  Serial.println("]}");
  if (!adxlOk) adxlOk = adxlInit();
  if (!oledOk) oledOk = oled.begin(SSD1306_SWITCHCAPVCC, 0x3C);
}

// ============================================================================= joystick (same logic as smart_cane.ino)
int joyDir = 0;
bool swDown = false, sosHeld = false;
unsigned long swAt = 0, swChanged = 0;

void sendBtn(const char *b, int beeps) {
  Serial.printf("{\"btn\":\"%s\"}\n", b);
  lastBtn = b;
  if (beeps > 0) play(1500, beeps, 40, 60);
}

void pollJoystick(unsigned long now) {
  int x = analogRead(PIN_JOY_X), y = analogRead(PIN_JOY_Y);
  int dir = 0;
  if (y < 700) dir = 1; else if (y > 3400) dir = 2; else if (x < 700) dir = 3; else if (x > 3400) dir = 4;
  if (dir != joyDir) {
    joyDir = dir;
    if (dir == 1) sendBtn("read", 2);
    if (dir == 2) sendBtn("ask", 3);
    if (dir == 3) sendBtn("repeat", 1);
    if (dir == 4) sendBtn("auto", 1);
  }
  bool down = digitalRead(PIN_SW) == LOW;
  if (down != swDown && now - swChanged > 30) {
    swChanged = now;
    swDown = down;
    if (down) { swAt = now; sosHeld = false; }
    else if (!sosHeld) sendBtn("describe", 1);
  }
  if (swDown && !sosHeld && now - swAt > SOS_HOLD_MS) {
    sosHeld = true;
    Serial.println("{\"btn\":\"sos\"}");
    lastBtn = "SOS";
    play(2500, 6, 150, 100);
  }
}

// ============================================================================= commands
void ack(const char *what) { Serial.printf("{\"ack\":\"%s\"}\n", what); }

void handle(String c) {
  c.trim();
  if (c.startsWith("tone ")) {
    int sp = c.indexOf(' ', 5);
    int hz = c.substring(5, sp > 0 ? sp : c.length()).toInt();
    int ms = sp > 0 ? c.substring(sp + 1).toInt() : 300;
    play(constrain(hz, 50, 8000), 1, constrain(ms, 10, 5000), 10);
    ack("tone");
  } else if (c == "sweep") {
    for (int f = 300; f <= 3000; f += 50) { tone(PIN_SPK, f); delay(12); }
    noTone(PIN_SPK); currentFreq = 0;
    ack("sweep");
  } else if (c.startsWith("beep")) {
    int n = c.length() > 5 ? c.substring(5).toInt() : 3;
    play(1500, constrain(n, 1, 10), 120, 120);
    ack("beep");
  } else if (c == "led") {
    for (int i = 0; i < 3; i++) { digitalWrite(PIN_LED, LOW); delay(200); digitalWrite(PIN_LED, HIGH); delay(200); }
    ack("led");
  } else if (c.startsWith("oled ")) {
    oledText = c.substring(5);
    ack("oled");
  } else if (c == "scan") {
    scanI2C();
  } else if (c.startsWith("alarmcm ")) {
    alarmCm = constrain((int)c.substring(8).toInt(), 4, 200);
    ack("alarmcm");
  } else if (c.startsWith("alarm ")) {
    alarmOn = c.substring(6).toInt() != 0;
    ack("alarm");
  }
}

void draw(float total) {
  if (!oledOk) return;
  oled.clearDisplay();
  oled.setTextColor(SSD1306_WHITE);
  oled.setTextSize(1);
  oled.setCursor(0, 0);
  oled.print("CANE LAB");
  oled.setCursor(70, 0);
  oled.print(alarmOn ? "alarm<" : "alarm off");
  if (alarmOn) oled.print(alarmCm);
  oled.setTextSize(3);
  oled.setCursor(0, 14);
  if (distanceCm < 0) oled.print("---");
  else { oled.print(distanceCm); oled.setTextSize(1); oled.print(" cm"); }
  oled.setTextSize(1);
  oled.setCursor(0, 44);
  oled.printf("g=%.2f  %s", total, lastBtn.c_str());
  oled.setCursor(0, 56);
  oled.print(oledText.length() ? oledText : "open cane-lab.html");
  oled.display();
}

// ============================================================================= main
long distBuf[3] = {-1, -1, -1};
int distIdx = 0;

void setup() {
  Serial.begin(115200);
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_IR, INPUT);
  pinMode(PIN_SW, INPUT_PULLUP);
  for (int p : SPARE) pinMode(p, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
  analogReadResolution(12);
  Wire.begin(PIN_SDA, PIN_SCL);
  adxlOk = adxlInit();
  oledOk = oled.begin(SSD1306_SWITCHCAPVCC, 0x3C);
}

void loop() {
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n') { handle(cmd); cmd = ""; }
    else if (ch != '\r' && cmd.length() < 64) cmd += ch;
  }

  unsigned long now = millis();
  pollJoystick(now);
  runSound(now);
  if (now - lastScan > 3000) { lastScan = now; scanI2C(); }
  if (now - lastSample < 50) return;
  lastSample = now;

  distBuf[distIdx] = readDistance();
  distIdx = (distIdx + 1) % 3;
  distanceCm = median3(distBuf[0], distBuf[1], distBuf[2]);
  swNow = digitalRead(PIN_SW);
  float ax = 0, ay = 0, az = 0;
  bool accOk = adxlOk && readAccel(ax, ay, az);
  float total = sqrtf(ax * ax + ay * ay + az * az);

  Serial.printf("{\"ms\":%lu,\"d\":%ld,\"ir\":%d,\"jx\":%d,\"jy\":%d,\"sw\":%d,\"acc\":%d,\"ax\":%.3f,\"ay\":%.3f,\"az\":%.3f,\"al\":%d,\"alcm\":%d,\"p\":{",
                now, distanceCm, digitalRead(PIN_IR), analogRead(PIN_JOY_X), analogRead(PIN_JOY_Y), swNow, accOk ? 1 : 0, ax, ay, az, alarmOn ? 1 : 0, alarmCm);
  for (size_t i = 0; i < sizeof(SPARE) / sizeof(SPARE[0]); i++) Serial.printf("%s\"%d\":%d", i ? "," : "", SPARE[i], digitalRead(SPARE[i]));
  Serial.println("}}");

  if (now - lastDraw > 250) { lastDraw = now; draw(total); }
}
