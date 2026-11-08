/*
 * Smart Eyes: HARDWARE TEST for the ESP32-C3 SuperMini cane.
 * Tests one part at a time from the Serial Monitor. Same pins as smart_cane.ino.
 *
 * Arduino IDE: board "ESP32C3 Dev Module", Tools > USB CDC On Boot: Enabled.
 * Serial Monitor: 115200 baud, line ending "Newline". Type a letter + Enter:
 *
 *   b  board check (blinks the blue LED 3x)
 *   p  speaker: plays a rising sweep, then 3 beeps
 *   u  ultrasonic: prints distance 5x per second
 *   i  IR: prints FLOOR / NO FLOOR (hole)
 *   j  joystick: prints X, Y (0-4095) and button
 *   s  I2C scan: should find 0x53 (ADXL345) and 0x3C (OLED)
 *   a  accelerometer: prints x, y, z in g and the total (about 1.00 when still)
 *   o  OLED: draws a test screen
 *   x  stop the current live test
 */

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// Right header: 4 speaker, 3 joystick button, 1 joystick Y, 0 joystick X
const int PIN_SPK = 4, PIN_SW = 3, PIN_JOY_Y = 1, PIN_JOY_X = 0;
// Left header: 6 SDA, 7 SCL, 10 IR, 20 TRIG, 21 ECHO (through the 1k/2k divider)
const int PIN_SDA = 6, PIN_SCL = 7, PIN_IR = 10, PIN_TRIG = 20, PIN_ECHO = 21;
const int PIN_LED = 8;
const uint8_t ADXL = 0x53;

Adafruit_SSD1306 oled(128, 64, &Wire, -1);
char live = 0;  // which live test is streaming
unsigned long lastPrint = 0;

void menu() {
  Serial.println();
  Serial.println(F("=== Smart Eyes hardware test ==="));
  Serial.println(F("b board  p speaker  u ultrasonic  i IR  j joystick"));
  Serial.println(F("s I2C scan  a accelerometer  o OLED  x stop"));
}

long distanceCm() {
  digitalWrite(PIN_TRIG, LOW); delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  unsigned long us = pulseIn(PIN_ECHO, HIGH, 25000UL);
  return us == 0 ? -1 : (long)(us / 58UL);
}

bool adxlStart() {
  Wire.beginTransmission(ADXL); Wire.write(0x00); Wire.endTransmission(false);
  if (Wire.requestFrom(ADXL, (uint8_t)1) != 1) return false;
  uint8_t id = Wire.read();
  if (id != 0xE5) { Serial.printf("ADXL345 answered with ID 0x%02X (expected 0xE5)\n", id); return false; }
  Wire.beginTransmission(ADXL); Wire.write(0x31); Wire.write(0x0B); Wire.endTransmission();
  Wire.beginTransmission(ADXL); Wire.write(0x2D); Wire.write(0x08); Wire.endTransmission();
  return true;
}

void setup() {
  Serial.begin(115200);
  delay(1500);  // give the USB serial time to connect
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_IR, INPUT);
  pinMode(PIN_SW, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, HIGH);
  analogReadResolution(12);
  Wire.begin(PIN_SDA, PIN_SCL);
  menu();
}

void loop() {
  if (Serial.available()) {
    char c = tolower(Serial.read());
    if (c == '\n' || c == '\r' || c == ' ') return;
    live = 0;
    noTone(PIN_SPK);
    switch (c) {
      case 'b':
        Serial.println(F("Blinking the blue LED 3 times. If you see it, the board and upload work."));
        for (int i = 0; i < 3; i++) { digitalWrite(PIN_LED, LOW); delay(250); digitalWrite(PIN_LED, HIGH); delay(250); }
        break;
      case 'p':
        Serial.println(F("Speaker: sweep 300 -> 3000 Hz, then 3 beeps. Silent? Check transistor pins (E-B-C) and 5V."));
        for (int f = 300; f <= 3000; f += 50) { tone(PIN_SPK, f); delay(12); }
        noTone(PIN_SPK); delay(300);
        for (int i = 0; i < 3; i++) { tone(PIN_SPK, 1500); delay(120); noTone(PIN_SPK); delay(120); }
        break;
      case 's': {
        Serial.println(F("Scanning I2C (SDA=6, SCL=7)..."));
        int found = 0;
        for (uint8_t addr = 1; addr < 127; addr++) {
          Wire.beginTransmission(addr);
          if (Wire.endTransmission() == 0) {
            found++;
            Serial.printf("  found 0x%02X %s\n", addr, addr == 0x53 ? "(ADXL345)" : addr == 0x3C || addr == 0x3D ? "(OLED)" : addr == 0x1D ? "(ADXL345 with SDO high: wire SDO to GND)" : "");
          }
        }
        if (!found) Serial.println(F("  nothing found: check SDA/SCL are not swapped, and 3.3V + GND"));
        break;
      }
      case 'o':
        if (!oled.begin(SSD1306_SWITCHCAPVCC, 0x3C)) { Serial.println(F("OLED not found at 0x3C. Run 's' to see its address.")); break; }
        oled.clearDisplay();
        oled.setTextColor(SSD1306_WHITE);
        oled.setTextSize(2); oled.setCursor(0, 0); oled.print("SMART EYES");
        oled.setTextSize(1); oled.setCursor(0, 30); oled.print("OLED works!");
        oled.drawRect(0, 44, 128, 20, SSD1306_WHITE);
        oled.display();
        Serial.println(F("OLED: you should see 'SMART EYES' and a box."));
        break;
      case 'a':
        if (!adxlStart()) { Serial.println(F("ADXL345 not found: CS->3.3V, SDO->GND, then run 's'.")); break; }
        Serial.println(F("Accelerometer live. Hold still: total ~1.00 g. Point the cane down: the shaft axis reads ~1.0 (x to stop)"));
        live = 'a';
        break;
      case 'u': Serial.println(F("Ultrasonic live: move your hand in front. -1 = nothing in range / ECHO not wired (x to stop)")); live = 'u'; break;
      case 'i': Serial.println(F("IR live: hold over the floor, then over a table edge (x to stop)")); live = 'i'; break;
      case 'j': Serial.println(F("Joystick live: centre ~2048, push each way, press the stick (x to stop)")); live = 'j'; break;
      case 'x': Serial.println(F("stopped")); break;
      default: menu();
    }
  }

  if (live && millis() - lastPrint > 200) {
    lastPrint = millis();
    if (live == 'u') Serial.printf("distance: %ld cm\n", distanceCm());
    if (live == 'i') Serial.println(digitalRead(PIN_IR) == LOW ? "IR: FLOOR (surface seen)" : "IR: NO FLOOR  <- hole");
    if (live == 'j') {
      int x = analogRead(PIN_JOY_X), y = analogRead(PIN_JOY_Y);
      const char *dir = y < 700 ? "UP" : y > 3400 ? "DOWN" : x < 700 ? "LEFT" : x > 3400 ? "RIGHT" : "centre";
      Serial.printf("x=%4d y=%4d  %-6s  button=%s\n", x, y, dir, digitalRead(PIN_SW) == LOW ? "PRESSED" : "up");
    }
    if (live == 'a') {
      Wire.beginTransmission(ADXL); Wire.write(0x32); Wire.endTransmission(false);
      if (Wire.requestFrom(ADXL, (uint8_t)6) == 6) {
        int16_t x = Wire.read() | (Wire.read() << 8);
        int16_t y = Wire.read() | (Wire.read() << 8);
        int16_t z = Wire.read() | (Wire.read() << 8);
        float gx = x * 0.0039f, gy = y * 0.0039f, gz = z * 0.0039f;
        Serial.printf("x=%5.2f y=%5.2f z=%5.2f  total=%4.2f g\n", gx, gy, gz, sqrtf(gx * gx + gy * gy + gz * gz));
      }
    }
  }
}
