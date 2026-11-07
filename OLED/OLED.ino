
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

// ESP32-C3 Super Mini I2C pins
#define SDA_PIN 6
#define SCL_PIN 7

// OLED configuration
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_ADDRESS 0x3C
#define OLED_RESET -1

Adafruit_SSD1306 display(
  SCREEN_WIDTH,
  SCREEN_HEIGHT,
  &Wire,
  OLED_RESET
);

int counter = 0;

void setup() {
  Serial.begin(115200);

  // Initialize I2C
  Wire.begin(SDA_PIN, SCL_PIN);

  // Initialize OLED
  if (!display.begin(
        SSD1306_SWITCHCAPVCC,
        OLED_ADDRESS)) {
    Serial.println("OLED initialization failed!");
    while (1);
  }

  Serial.println("OLED initialized!");

  // Initial screen
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  display.setTextSize(2);
  display.setCursor(10, 10);
  display.println("Hello!");

  display.setTextSize(1);
  display.setCursor(10, 40);
  display.println("ESP32-C3 Super Mini");

  display.display();

  delay(2000);
}

void loop() {
  display.clearDisplay();

  // Title
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("ESP32-C3 OLED DEMO");

  display.drawLine(0, 12, 127, 12, SSD1306_WHITE);

  // Counter
  display.setTextSize(2);
  display.setCursor(10, 20);
  display.print("Count: ");
  display.println(counter);

  // Progress bar
  int progress = counter % 101;

  display.drawRect(10, 48, 108, 10, SSD1306_WHITE);
  display.fillRect(12, 50, progress, 6, SSD1306_WHITE);

  display.display();

  Serial.print("Counter: ");
  Serial.println(counter);

  counter++;

  delay(500);
}
