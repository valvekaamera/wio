#include <Arduino.h>
#include "TFT_eSPI.h"
#include "rpcWiFi.h"
#if __has_include("wifi_secrets.h")
#include "wifi_secrets.h"
#endif
#ifndef WIFI_SSID
#define WIFI_SSID ""
#endif
#ifndef WIFI_PASSWORD
#define WIFI_PASSWORD ""
#endif
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
  Serial.println("  wifi version                  - RTL8720 firmware version");
  Serial.println("  wifi scan                     - list nearby access points");
  Serial.println("  wifi connect [ssid] [password] - join AP (defaults from wifi_secrets.h)");
  Serial.println("  wifi status                   - connection state, IP, RSSI");
  Serial.println("  wifi disconnect               - leave the current AP");
  Serial.println();
}
const char* wifiStatusName(int status) {
  switch (status) {
    case WL_CONNECTED:       return "CONNECTED";
    case WL_NO_SSID_AVAIL:   return "NO_SSID_AVAIL";
    case WL_CONNECT_FAILED:  return "CONNECT_FAILED";
    case WL_CONNECTION_LOST: return "CONNECTION_LOST";
    case WL_DISCONNECTED:    return "DISCONNECTED";
    case WL_IDLE_STATUS:     return "IDLE";
    case WL_NO_SHIELD:       return "NO_SHIELD";
    default:                 return "UNKNOWN";
  }
}
void wifiPrintStatus() {
  const int status = WiFi.status();
  Serial.print("WiFi status: ");
  Serial.println(wifiStatusName(status));
  if (status == WL_CONNECTED) {
    Serial.print("  SSID : "); Serial.println(WiFi.SSID());
    Serial.print("  IP   : "); Serial.println(WiFi.localIP());
    Serial.print("  GW   : "); Serial.println(WiFi.gatewayIP());
    Serial.print("  RSSI : "); Serial.print(WiFi.RSSI()); Serial.println(" dBm");
  }
}
void wifiScan() {
  Serial.println("Scanning...");
  WiFi.mode(WIFI_STA);
  const int count = WiFi.scanNetworks();
  if (count <= 0) {
    Serial.println("No networks found.");
    return;
  }
  Serial.print(count);
  Serial.println(" network(s):");
  for (int i = 0; i < count; ++i) {
    Serial.print("  ");
    Serial.print(i + 1);
    Serial.print(") ");
    Serial.print(WiFi.SSID(i));
    Serial.print("  RSSI=");
    Serial.print(WiFi.RSSI(i));
    Serial.print(" dBm  ");
    Serial.println(WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "open" : "secured");
  }
  WiFi.scanDelete();
}
void wifiConnect(String ssid, String password) {
  if (ssid.length() == 0) {
    ssid = WIFI_SSID;
    password = WIFI_PASSWORD;
  }
  if (ssid.length() == 0) {
    Serial.println("No SSID given and wifi_secrets.h not present.");
    Serial.println("Usage: wifi connect <ssid> <password>");
    return;
  }
  Serial.print("Connecting to '");
  Serial.print(ssid);
  Serial.print("'");
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  delay(100);
  WiFi.begin(ssid.c_str(), password.c_str());
  const unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < 20000UL) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  wifiPrintStatus();
}
void handleWifiCommand(const String& sub, const String& args) {
  if (sub == "version") {
    Serial.print("RTL8720 firmware: ");
    Serial.println(rpc_system_version());
    return;
  }
  if (sub == "scan") {
    wifiScan();
    return;
  }
  if (sub == "connect") {
    String ssid = args;
    String password;
    const int space = args.indexOf(' ');
    if (space >= 0) {
      ssid = args.substring(0, space);
      password = args.substring(space + 1);
      password.trim();
    }
    wifiConnect(ssid, password);
    return;
  }
  if (sub == "status") {
    wifiPrintStatus();
    return;
  }
  if (sub == "disconnect") {
    WiFi.disconnect();
    Serial.println("Disconnected.");
    return;
  }
  Serial.println("Unknown wifi subcommand. Try: version, scan, connect, status, disconnect");
}
void handleSerialCommand(const String& command) {
  if (command.startsWith("wifi")) {
    String rest = command.substring(4);
    rest.trim();
    String sub = rest;
    String args;
    const int space = rest.indexOf(' ');
    if (space >= 0) {
      sub = rest.substring(0, space);
      args = rest.substring(space + 1);
      args.trim();
    }
    sub.toLowerCase();
    handleWifiCommand(sub, args);
    return;
  }
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
    if (trimmed.length() > 0) {
      // Lowercase only the leading keyword so SSIDs/passwords keep their case.
      String head = trimmed.substring(0, 4);
      head.toLowerCase();
      String normalized = trimmed;
      if (head == "wifi") {
        normalized = "wifi" + trimmed.substring(4);
      } else {
        normalized.toLowerCase();
      }
      handleSerialCommand(normalized);
    }
  }
}
