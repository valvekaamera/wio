#include <Arduino.h>
#include "TFT_eSPI.h"
TFT_eSPI tft;
#define STATUS_LED LED_BUILTIN
namespace {
constexpr uint16_t kBgColor = TFT_NAVY;
constexpr uint16_t kAccentColor = TFT_CYAN;
constexpr uint16_t kTextColor = TFT_WHITE;
constexpr uint16_t kMutedColor = TFT_DARKGREY;
unsigned long bootMillis = 0;
unsigned long lastHeartbeatMs = 0;
uint32_t heartbeatCount = 0;
void drawHeader() {
  tft.fillRect(0, 0, tft.width(), 40, kAccentColor);
  tft.setTextColor(TFT_BLACK, kAccentColor);
  tft.setTextSize(2);
  tft.setTextDatum(MC_DATUM);
  tft.drawString("Wio Terminal D51R", tft.width() / 2, 20);
}
void drawStaticBody() {
  tft.fillRect(0, 40, tft.width(), tft.height() - 40, kBgColor);
  tft.setTextDatum(TL_DATUM);
  tft.setTextSize(1);
  tft.setTextColor(kTextColor, kBgColor);
  tft.drawString("MCU: ATSAMD51P19A", 10, 54);
  tft.drawString("CPU: 120 MHz", 10, 72);
  tft.drawString("USB: Connected", 10, 90);
  tft.drawString("Serial: 115200 baud", 10, 108);
  tft.drawLine(10, 126, tft.width() - 10, 126, kMutedColor);
  tft.setTextColor(kAccentColor, kBgColor);
  tft.drawString("Open serial monitor to send commands.", 10, 138);
  tft.drawString("Type 'help' for the command list.", 10, 154);
}
void drawFooter(const String& uptime, const String& heartbeat) {
  const int footerY = tft.height() - 44;
  tft.fillRect(0, footerY, tft.width(), 44, TFT_BLACK);
  tft.setTextDatum(TL_DATUM);
  tft.setTextSize(1);
  tft.setTextColor(kTextColor, TFT_BLACK);
  tft.drawString("Uptime: " + uptime, 10, footerY + 8);
  tft.drawString("Heartbeat: " + heartbeat, 10, footerY + 24);
}
String formatUptime(unsigned long ms) {
  const unsigned long totalSeconds = ms / 1000UL;
  const unsigned long hours = totalSeconds / 3600UL;
  const unsigned long minutes = (totalSeconds % 3600UL) / 60UL;
  const unsigned long seconds = totalSeconds % 60UL;
  char buffer[16];
  snprintf(buffer, sizeof(buffer), "%02lu:%02lu:%02lu", hours, minutes, seconds);
  return String(buffer);
}
void printBanner() {
  Serial.println();
  Serial.println("======================================");
  Serial.println(" Wio Terminal D51R - USB Ready");
  Serial.println(" MCU : ATSAMD51P19A @ 120 MHz");
  Serial.println("======================================");
  Serial.println("Commands:");
  Serial.println("  help      - show this help");
  Serial.println("  info      - print device info");
  Serial.println("  led on    - turn status LED on");
  Serial.println("  led off   - turn status LED off");
  Serial.println("  reboot    - reset the board");
  Serial.println();
}
void handleSerialCommand(const String& command) {
  if (command == "help") {
    printBanner();
    return;
  }
  if (command == "info") {
    Serial.println("Board: Seeed Wio Terminal D51R");
    Serial.println("MCU: ATSAMD51P19A");
    Serial.println("Framework: Arduino (Seeed SAMD core)");
    Serial.print("Uptime (ms): ");
    Serial.println(millis() - bootMillis);
    Serial.print("Heartbeat count: ");
    Serial.println(heartbeatCount);
    return;
  }
  if (command == "led on") {
    digitalWrite(STATUS_LED, HIGH);
    Serial.println("Status LED ON");
    return;
  }
  if (command == "led off") {
    digitalWrite(STATUS_LED, LOW);
    Serial.println("Status LED OFF");
    return;
  }
  if (command == "reboot") {
    Serial.println("Rebooting...");
    Serial.flush();
    delay(100);
    NVIC_SystemReset();
    return;
  }
  Serial.print("Unknown command: ");
  Serial.println(command);
  Serial.println("Type 'help' for available commands.");
}
}  // namespace
void setup() {
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, LOW);
  pinMode(LCD_BACKLIGHT, OUTPUT);
  digitalWrite(LCD_BACKLIGHT, HIGH);
  Serial.begin(115200);
  while (!Serial && (millis() < 3000UL)) {
    delay(10);
  }
  bootMillis = millis();
  tft.begin();
  tft.setRotation(3);
  tft.fillScreen(kBgColor);
  drawHeader();
  drawStaticBody();
  drawFooter("00:00:00", "#0");
  digitalWrite(STATUS_LED, HIGH);
  printBanner();
  Serial.println("Device is ready.");
}
void loop() {
  const unsigned long now = millis();
  if (now - lastHeartbeatMs >= 1000UL) {
    lastHeartbeatMs = now;
    heartbeatCount++;
    drawFooter(formatUptime(now - bootMillis), "#" + String(heartbeatCount));
    Serial.print("[heartbeat ");
    Serial.print(heartbeatCount);
    Serial.print("] uptime=");
    Serial.println(formatUptime(now - bootMillis));
  }
  if (Serial.available()) {
    const String command = Serial.readStringUntil('\n');
    String trimmed = command;
    trimmed.trim();
    trimmed.toLowerCase();
    if (trimmed.length() > 0) {
      handleSerialCommand(trimmed);
    }
  }
}
