#include <Arduino.h>
#include "TFT_eSPI.h"
#include "rpcWiFi.h"
#include "rpcPing.h"
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
// Host on the LAN used to prove the Wio Terminal can reach other machines.
const IPAddress kPingTarget(192, 168, 150, 25);
constexpr uint8_t kPingCount = 4;

// ---- Call emulation (FNOL = First Notice Of Loss) ----------------------
// A = pick up (start "recording"), B = hang up, C = send to transcription.
enum class CallState { Idle, Recording, Sending };
CallState callState = CallState::Idle;
unsigned long recordStartMs = 0;
unsigned long sendingStartMs = 0;
unsigned long lastRecordTickMs = 0;
constexpr unsigned long kSendingDisplayMs = 3000UL;
constexpr unsigned long kDebounceMs = 40UL;
constexpr int kHeaderHeight = 40;
constexpr int kFooterHeight = 44;

struct Button {
  uint8_t pin;
  const char* name;
  bool stableLow;        // debounced pressed state (active LOW)
  bool lastReadingLow;
  unsigned long lastChangeMs;
};
Button buttons[] = {
    {WIO_KEY_A, "A", false, false, 0},
    {WIO_KEY_B, "B", false, false, 0},
    {WIO_KEY_C, "C", false, false, 0},
};

String formatUptime(unsigned long ms) {
  const unsigned long totalSeconds = ms / 1000UL;
  const unsigned long hours = totalSeconds / 3600UL;
  const unsigned long minutes = (totalSeconds % 3600UL) / 60UL;
  const unsigned long seconds = totalSeconds % 60UL;
  char buffer[16];
  snprintf(buffer, sizeof(buffer), "%02lu:%02lu:%02lu", hours, minutes, seconds);
  return String(buffer);
}
String formatElapsed(unsigned long ms) {
  const unsigned long totalSeconds = ms / 1000UL;
  char buffer[16];
  snprintf(buffer, sizeof(buffer), "%02lu:%02lu", totalSeconds / 60UL, totalSeconds % 60UL);
  return String(buffer);
}
int bodyTop() { return kHeaderHeight; }
int bodyHeight() { return tft.height() - kHeaderHeight - kFooterHeight; }
int bodyCenterY() { return bodyTop() + bodyHeight() / 2; }

void drawHeader() {
  tft.fillRect(0, 0, tft.width(), kHeaderHeight, kAccentColor);
  tft.setTextColor(TFT_BLACK, kAccentColor);
  tft.setTextSize(2);
  tft.setTextDatum(MC_DATUM);
  tft.drawString("Wio Terminal D51R", tft.width() / 2, kHeaderHeight / 2);
}
void clearBody() {
  tft.fillRect(0, bodyTop(), tft.width(), bodyHeight(), kBgColor);
}
void drawIdleBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(3);
  tft.setTextColor(kTextColor, kBgColor);
  tft.drawString("Waiting for FNOL", tft.width() / 2, bodyCenterY());
}
void drawRecordingElapsed(unsigned long elapsedMs) {
  // Only the time string is redrawn each second to avoid flicker.
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(3);
  tft.setTextColor(kTextColor, kBgColor);
  tft.setTextPadding(6 * 3 * 6);  // width of "00:00" at size 3, clears old digits
  tft.drawString(formatElapsed(elapsedMs), tft.width() / 2, bodyCenterY() + 30);
  tft.setTextPadding(0);
}
void drawRecordingBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(4);
  tft.setTextColor(TFT_RED, kBgColor);
  const int labelY = bodyCenterY() - 22;
  tft.drawString("rec", tft.width() / 2 + 14, labelY);
  tft.fillCircle(tft.width() / 2 - 48, labelY, 9, TFT_RED);
  drawRecordingElapsed(0);
}
void drawSendingBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(3);
  tft.setTextColor(kAccentColor, kBgColor);
  tft.drawString("Sending FNOL", tft.width() / 2, bodyCenterY() - 18);
  tft.drawString("to transcription", tft.width() / 2, bodyCenterY() + 18);
}
void drawFooter(const String& uptime) {
  const int footerY = tft.height() - kFooterHeight;
  tft.fillRect(0, footerY, tft.width(), kFooterHeight, TFT_BLACK);
  tft.setTextDatum(TL_DATUM);
  tft.setTextSize(1);
  tft.setTextColor(kTextColor, TFT_BLACK);
  tft.drawString("Uptime: " + uptime, 10, footerY + 8);
  tft.setTextColor(kMutedColor, TFT_BLACK);
  tft.drawString("A: pick up   B: hang up   C: send FNOL", 10, footerY + 24);
}
void enterState(CallState next) {
  const unsigned long now = millis();
  callState = next;
  switch (next) {
    case CallState::Idle:
      drawIdleBody();
      Serial.println("[call] idle - waiting for FNOL");
      break;
    case CallState::Recording:
      recordStartMs = now;
      lastRecordTickMs = now;
      drawRecordingBody();
      Serial.println("[call] picked up - recording");
      break;
    case CallState::Sending:
      sendingStartMs = now;
      drawSendingBody();
      Serial.println("[call] sending FNOL to transcription");
      break;
  }
}
void onButtonPressed(const Button& button) {
  Serial.print("[button] ");
  Serial.println(button.name);
  if (button.pin == WIO_KEY_A) {
    if (callState == CallState::Idle) enterState(CallState::Recording);
  } else if (button.pin == WIO_KEY_B) {
    if (callState == CallState::Recording) {
      Serial.print("[call] hung up after ");
      Serial.println(formatElapsed(millis() - recordStartMs));
      enterState(CallState::Idle);
    }
  } else if (button.pin == WIO_KEY_C) {
    if (callState != CallState::Sending) enterState(CallState::Sending);
  }
}
void pollButtons(unsigned long now) {
  for (Button& button : buttons) {
    const bool readingLow = digitalRead(button.pin) == LOW;
    if (readingLow != button.lastReadingLow) {
      button.lastReadingLow = readingLow;
      button.lastChangeMs = now;
    }
    if (now - button.lastChangeMs >= kDebounceMs && readingLow != button.stableLow) {
      button.stableLow = readingLow;
      if (readingLow) onButtonPressed(button);  // falling edge = press
    }
  }
}
void updateCallState(unsigned long now) {
  if (callState == CallState::Recording && now - lastRecordTickMs >= 1000UL) {
    lastRecordTickMs = now;
    drawRecordingElapsed(now - recordStartMs);
  }
  if (callState == CallState::Sending && now - sendingStartMs >= kSendingDisplayMs) {
    enterState(CallState::Idle);
  }
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
  Serial.println("  ping [ip]                     - ICMP ping (default 192.168.150.25)");
  Serial.println();
}
void pingHost(const String& arg) {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Not connected. Run 'wifi connect' first.");
    return;
  }
  IPAddress target = kPingTarget;
  if (arg.length() > 0 && !target.fromString(arg)) {
    Serial.print("Invalid IP address: ");
    Serial.println(arg);
    return;
  }
  Serial.print("PING ");
  Serial.print(target);
  Serial.print(" x");
  Serial.print(kPingCount);
  Serial.println(" ...");
  const bool ok = Ping.ping(target, kPingCount);
  if (ok) {
    Serial.print("Reply from ");
    Serial.print(target);
    Serial.print(": avg ");
    Serial.print(Ping.averageTime(), 1);
    Serial.println(" ms");
  } else {
    Serial.print("No reply from ");
    Serial.print(target);
    Serial.println(" (host down, firewall, or wrong subnet?)");
  }
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
  if (command == "ping" || command.startsWith("ping ")) {
    String arg = command.substring(4);
    arg.trim();
    pingHost(arg);
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
  for (Button& button : buttons) {
    pinMode(button.pin, INPUT_PULLUP);
  }
  bootMillis = millis();
  tft.begin();
  tft.setRotation(3);
  tft.fillScreen(kBgColor);
  drawHeader();
  drawFooter("00:00:00");
  enterState(CallState::Idle);
  digitalWrite(STATUS_LED, HIGH);
  printBanner();
  Serial.println("Device is ready.");
}
void loop() {
  const unsigned long now = millis();
  if (now - lastHeartbeatMs >= 1000UL) {
    lastHeartbeatMs = now;
    heartbeatCount++;
    drawFooter(formatUptime(now - bootMillis));
  }
  pollButtons(now);
  updateCallState(now);
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
