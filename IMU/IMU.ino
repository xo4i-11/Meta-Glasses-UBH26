
#include <Wire.h>

#define ADXL345_ADDRESS 0x53

#define POWER_CTL   0x2D
#define DATA_FORMAT 0x31
#define DATAX0      0x32
#define DEVICE_ID   0x00

float offsetX = 0;
float offsetY = 0;
float offsetZ = 0;

void writeRegister(byte reg, byte value) {
  Wire.beginTransmission(ADXL345_ADDRESS);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}

byte readRegister(byte reg) {
  Wire.beginTransmission(ADXL345_ADDRESS);
  Wire.write(reg);
  Wire.endTransmission(false);

  Wire.requestFrom(ADXL345_ADDRESS, (byte)1);

  if (Wire.available()) {
    return Wire.read();
  }

  return 0;
}

void readAcceleration(int16_t &x, int16_t &y, int16_t &z) {
  Wire.beginTransmission(ADXL345_ADDRESS);
  Wire.write(DATAX0);
  Wire.endTransmission(false);

  Wire.requestFrom(ADXL345_ADDRESS, (byte)6);

  if (Wire.available() == 6) {
    x = (int16_t)(Wire.read() | (Wire.read() << 8));
    y = (int16_t)(Wire.read() | (Wire.read() << 8));
    z = (int16_t)(Wire.read() | (Wire.read() << 8));
  }
}

void calibrate() {
  long sumX = 0;
  long sumY = 0;
  long sumZ = 0;

  const int samples = 1000;

  Serial.println("Keep sensor stationary...");
  delay(2000);

  for (int i = 0; i < samples; i++) {
    int16_t x, y, z;

    readAcceleration(x, y, z);

    sumX += x;
    sumY += y;
    sumZ += z;

    delay(5);
  }

  offsetX = (float)sumX / samples;
  offsetY = (float)sumY / samples;
  offsetZ = (float)sumZ / samples;

  // Assuming sensor is flat, Z should measure approximately +1g.
  // ADXL345 default full-resolution sensitivity is approximately
  // 256 counts/g.
  offsetZ -= 256.0;

  Serial.println("\nCalibration complete!");
  Serial.print("X offset: "); Serial.println(offsetX);
  Serial.print("Y offset: "); Serial.println(offsetY);
  Serial.print("Z offset: "); Serial.println(offsetZ);
}

void setup() {
  Serial.begin(115200);
  Wire.begin();

  delay(1000);

  Serial.println("ADXL345 Diagnostic");

  byte id = readRegister(DEVICE_ID);

  Serial.print("Device ID: 0x");
  Serial.println(id, HEX);

  if (id != 0xE5) {
    Serial.println("ADXL345 not detected!");
    while (1);
  }

  Serial.println("ADXL345 detected!");

  // Set measurement range to +/- 2g
  writeRegister(DATA_FORMAT, 0x08);

  // Enable measurement mode
  writeRegister(POWER_CTL, 0x08);

  delay(100);

  calibrate();
}

void loop() {
  int16_t x, y, z;

  readAcceleration(x, y, z);

  float ax = (x - offsetX) / 256.0;
  float ay = (y - offsetY) / 256.0;
  float az = (z - offsetZ) / 256.0;

  Serial.print("X: ");
  Serial.print(ax, 3);

  Serial.print(" g | Y: ");
  Serial.print(ay, 3);

  Serial.print(" g | Z: ");
  Serial.print(az, 3);

  Serial.println(" g");

  delay(100);
}
