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
#include "app_config.h"
#include "audio_stream.h"
#include "mic_capture.h"
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
// C = pick up (start "recording"), B = hang up, A = send to transcription.
enum class CallState { Idle, Recording, HungUp, Sending };
CallState callState = CallState::Idle;
unsigned long recordStartMs = 0;
unsigned long transientStartMs = 0;  // start of HungUp / Sending overlay
unsigned long lastRecordTickMs = 0;
constexpr unsigned long kHungUpDisplayMs = 2000UL;
constexpr unsigned long kSendingDisplayMs = 5000UL;
CallState stateBeforeSending = CallState::Idle;  // where to return after Sending
constexpr unsigned long kDebounceMs = 40UL;
constexpr int kHeaderHeight = 40;
constexpr int kFooterHeight = 44;
constexpr unsigned long kWifiConnectTimeoutMs = 15000UL;
constexpr int kTranscriptLines = 3;
constexpr int kTranscriptCols = 52;               // 320 px / 6 px per char at size 1
String lastTranscript;                            // ASCII-folded for the LCD
bool transcriptDirty = false;
// Claim-advisor verdict for the latest segment; headline replaces the first transcript
// line, detail (closing phrase or first follow-up question) replaces the transcript tail.
String advisorHeadline;
String advisorDetail;
uint16_t advisorColor = TFT_WHITE;
const char* wifiStatusName(int status);

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
// Recording screen layout (body spans y=40..196):
//   y=66   red dot + "rec"          y=100  mm:ss timer
//   y=120  level bar                y=132  backend status line
//   y=148+ transcript (3 lines)
constexpr int kRecLabelY = 66;
constexpr int kRecTimerY = 100;
constexpr int kRecLevelY = 120;
constexpr int kRecStatusY = 132;
constexpr int kRecTranscriptY = 148;

void drawRecordingElapsed(unsigned long elapsedMs) {
  // Only the time string is redrawn each second to avoid flicker.
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(3);
  tft.setTextColor(kTextColor, kBgColor);
  tft.setTextPadding(6 * 3 * 6);  // width of "00:00" at size 3, clears old digits
  tft.drawString(formatElapsed(elapsedMs), tft.width() / 2, kRecTimerY);
  tft.setTextPadding(0);
}
void drawRecordingLevel(uint16_t peak) {
  const int x0 = 40, w = tft.width() - 80, h = 6;
  const int fill = static_cast<int>((static_cast<uint32_t>(peak) * w) / 32768UL);
  tft.fillRect(x0, kRecLevelY, w, h, TFT_BLACK);
  tft.fillRect(x0, kRecLevelY, fill, h, peak > 30000 ? TFT_RED : TFT_GREEN);
}
void drawRecordingStatus() {
  String line;
  uint16_t color = kMutedColor;
  switch (stream::state()) {
    case stream::State::Connecting:
      line = String("backend ") + stream::host() + ":" + stream::port() + " connecting...";
      color = TFT_ORANGE;
      break;
    case stream::State::Streaming:
      line = String("streaming to ") + stream::host() + "  " + String(stream::bytesSent() / 1024) + " KB";
      if (stream::framesDropped() > 0) line += "  drop " + String(stream::framesDropped());
      color = kAccentColor;
      break;
    case stream::State::Stopping:
      line = "closing session...";
      break;
    default:
      line = "OFFLINE - audio not sent";
      color = TFT_RED;
      break;
  }
  if (mic::overruns() > 0) line += "  ovr " + String(mic::overruns());
  tft.setTextDatum(TL_DATUM);
  tft.setTextSize(1);
  tft.setTextColor(color, kBgColor);
  tft.setTextPadding(tft.width() - 20);
  tft.drawString(line, 10, kRecStatusY);
  tft.setTextPadding(0);
}
// Word-wrap `text` into at most `maxLines` lines of kTranscriptCols and draw them at `y`,
// keeping the tail when it does not fit (the end of a sentence carries the news).
void drawWrappedTail(const String& text, int y, int maxLines, uint16_t color) {
  if (text.length() == 0 || maxLines <= 0) return;
  tft.setTextDatum(TL_DATUM);
  tft.setTextSize(1);
  tft.setTextColor(color, kBgColor);
  String lines[kTranscriptLines];
  int count = 0;
  String current;
  int start = 0;
  while (start <= static_cast<int>(text.length())) {
    int end = text.indexOf(' ', start);
    if (end < 0) end = text.length();
    String word = text.substring(start, end);
    if (current.length() + word.length() + 1 > kTranscriptCols && current.length() > 0) {
      lines[count % kTranscriptLines] = current;
      count++;
      current = word;
    } else {
      current = current.length() ? current + " " + word : word;
    }
    start = end + 1;
  }
  lines[count % kTranscriptLines] = current;
  count++;
  const int shown = count < maxLines ? count : maxLines;
  for (int i = 0; i < shown; ++i) {
    const String& l = lines[(count - shown + i) % kTranscriptLines];
    tft.drawString(l, 10, y + i * 12);
  }
}
void drawRecordingTranscript() {
  tft.fillRect(0, kRecTranscriptY, tft.width(), bodyTop() + bodyHeight() - kRecTranscriptY, kBgColor);
  int y = kRecTranscriptY;
  int linesLeft = kTranscriptLines + 1;   // 4 x 12 px fit between y=148 and the footer
  if (advisorHeadline.length() > 0) {
    tft.setTextDatum(TL_DATUM);
    tft.setTextSize(1);
    tft.setTextColor(advisorColor, kBgColor);
    tft.drawString(advisorHeadline, 10, y);
    y += 12;
    linesLeft--;
  }
  if (advisorDetail.length() > 0) {
    drawWrappedTail(advisorDetail, y, linesLeft, kTextColor);
  } else {
    drawWrappedTail(lastTranscript, y, linesLeft, kTextColor);
  }
}
void drawRecordingBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(4);
  tft.setTextColor(TFT_RED, kBgColor);
  tft.drawString("rec", tft.width() / 2 + 14, kRecLabelY);
  tft.fillCircle(tft.width() / 2 - 48, kRecLabelY, 9, TFT_RED);
  drawRecordingElapsed(0);
  drawRecordingLevel(0);
  drawRecordingStatus();
  drawRecordingTranscript();
}
void drawConnectingWifiBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(2);
  tft.setTextColor(TFT_ORANGE, kBgColor);
  tft.drawString("Connecting Wi-Fi...", tft.width() / 2, bodyCenterY() - 10);
  tft.setTextSize(1);
  tft.setTextColor(kMutedColor, kBgColor);
  tft.drawString(WIFI_SSID, tft.width() / 2, bodyCenterY() + 14);
}
// TFT_eSPI's built-in font has no UTF-8 glyphs; fold Finnish letters for the LCD only.
String asciiFold(const char* utf8) {
  String out;
  for (const unsigned char* p = reinterpret_cast<const unsigned char*>(utf8); *p; ++p) {
    if (*p == 0xC3 && p[1]) {
      const unsigned char c = p[1];
      ++p;
      switch (c) {
        case 0xA4: case 0xA5: out += 'a'; break;   // ä å
        case 0xB6: out += 'o'; break;              // ö
        case 0x84: case 0x85: out += 'A'; break;   // Ä Å
        case 0x96: out += 'O'; break;              // Ö
        default: out += '?'; break;
      }
    } else if (*p < 0x80) {
      out += static_cast<char>(*p);
    } else if ((*p & 0xC0) != 0x80) {
      out += '?';                                  // other multi-byte lead byte
    }
  }
  return out;
}
bool ensureWifiConnected() {
  if (WiFi.status() == WL_CONNECTED) return true;
  if (strlen(WIFI_SSID) == 0) {
    Serial.println("[wifi] no credentials (wifi_secrets.h missing) - recording offline");
    return false;
  }
  Serial.print("[wifi] connecting to ");
  Serial.println(WIFI_SSID);
  drawConnectingWifiBody();
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  const unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < kWifiConnectTimeoutMs) {
    delay(200);
  }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("[wifi] connected, IP ");
    Serial.println(WiFi.localIP());
    return true;
  }
  Serial.println("[wifi] connection failed - recording offline");
  return false;
}
void drawHungUpBody() {
  clearBody();
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(3);
  tft.setTextColor(kTextColor, kBgColor);
  tft.drawString("Hang up", tft.width() / 2, bodyCenterY());
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
  String line = "Uptime " + uptime + "   WiFi: ";
  if (WiFi.status() == WL_CONNECTED) {
    line += WiFi.localIP().toString();
  } else if (strlen(WIFI_SSID) == 0) {
    line += "no config";
  } else {
    line += "offline";
  }
  tft.drawString(line, 10, footerY + 8);
  tft.setTextColor(kMutedColor, TFT_BLACK);
  tft.drawString("C: pick up   B: hang up   A: send FNOL", 10, footerY + 24);
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
      lastTranscript = "";
      advisorHeadline = "";
      advisorDetail = "";
      ensureWifiConnected();
      mic::start();
      stream::startSession();
      recordStartMs = millis();
      lastRecordTickMs = recordStartMs;
      drawRecordingBody();
      Serial.println("[call] picked up - recording + streaming");
      break;
    case CallState::HungUp:
      transientStartMs = now;
      mic::stop();
      stream::stopSession();
      drawHungUpBody();
      Serial.println("[call] hung up");
      break;
    case CallState::Sending:
      transientStartMs = now;
      drawSendingBody();
      if (stream::state() == stream::State::Streaming) {
        stream::requestTranscribe();
        Serial.println("[call] sending FNOL to transcription");
      } else {
        Serial.println("[call] send requested but no active stream - nothing sent");
      }
      break;
  }
}
void onTranscriptReceived(int segment, const char* text) {
  (void)segment;
  lastTranscript = asciiFold(text);
  // A new segment starts a new advisor round; the "WORKING" event follows right after.
  advisorHeadline = "";
  advisorDetail = "";
  transcriptDirty = true;
}
void onAdvisorReceived(stream::AdvisorStatus status, const char* message, int questionCount,
                       const char* firstQuestion) {
  switch (status) {
    case stream::AdvisorStatus::Working:
      advisorHeadline = "Analysoidaan FNOL...";
      advisorDetail = "";                       // keep showing the transcript meanwhile
      advisorColor = TFT_ORANGE;
      break;
    case stream::AdvisorStatus::Ready:
      advisorHeadline = "KORVAUSRATKAISU VALMIS  ->  B: hang up";
      advisorDetail = String("Sano: \"") + asciiFold(message) + "\"";
      advisorColor = TFT_GREEN;
      break;
    case stream::AdvisorStatus::Questions:
      advisorHeadline = "LISAKYSYMYKSIA: " + String(questionCount) + "  (kaikki palvelinlokissa)";
      advisorDetail = String("1. ") + asciiFold(firstQuestion);
      advisorColor = TFT_ORANGE;
      break;
    case stream::AdvisorStatus::Error:
      advisorHeadline = "Neuvoja ei vastannut";
      advisorDetail = asciiFold(message);
      advisorColor = TFT_RED;
      break;
  }
  transcriptDirty = true;
}
void onStreamStatus(stream::State state) {
  (void)state;
  if (callState == CallState::Recording) drawRecordingStatus();
}
// Return to Recording after an overlay without resetting the call timer.
void resumeRecording() {
  callState = CallState::Recording;
  lastRecordTickMs = millis();
  drawRecordingBody();
  drawRecordingElapsed(millis() - recordStartMs);
  Serial.println("[call] back to recording");
}
void onButtonPressed(const Button& button) {
  Serial.print("[button] ");
  Serial.println(button.name);
  if (button.pin == WIO_KEY_C) {
    if (callState == CallState::Idle) enterState(CallState::Recording);
  } else if (button.pin == WIO_KEY_B) {
    if (callState == CallState::Recording) {
      Serial.print("[call] call duration ");
      Serial.println(formatElapsed(millis() - recordStartMs));
      enterState(CallState::HungUp);
    }
  } else if (button.pin == WIO_KEY_A) {
    if (callState == CallState::Idle || callState == CallState::Recording) {
      stateBeforeSending = callState;
      enterState(CallState::Sending);
    }
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
    drawRecordingLevel(mic::takePeak());
    drawRecordingStatus();
  }
  if (callState == CallState::Recording && transcriptDirty) {
    transcriptDirty = false;
    drawRecordingTranscript();
  }
  if (callState == CallState::HungUp && now - transientStartMs >= kHungUpDisplayMs) {
    enterState(CallState::Idle);
  }
  if (callState == CallState::Sending && now - transientStartMs >= kSendingDisplayMs) {
    if (stateBeforeSending == CallState::Recording) {
      resumeRecording();
    } else {
      enterState(CallState::Idle);
    }
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
  Serial.println("  status                        - call state, mic, stream and Wi-Fi summary");
  Serial.println();
  Serial.print("Backend: ws://");
  Serial.print(BACKEND_HOST);
  Serial.print(":");
  Serial.print(BACKEND_PORT);
  Serial.println(BACKEND_WS_PATH);
  Serial.println();
}
const char* callStateName(CallState s) {
  switch (s) {
    case CallState::Idle: return "Idle";
    case CallState::Recording: return "Recording";
    case CallState::HungUp: return "HungUp";
    case CallState::Sending: return "Sending";
  }
  return "?";
}
const char* streamStateName(stream::State s) {
  switch (s) {
    case stream::State::Idle: return "Idle";
    case stream::State::Connecting: return "Connecting";
    case stream::State::Streaming: return "Streaming";
    case stream::State::Stopping: return "Stopping";
  }
  return "?";
}
void printStatus() {
  Serial.print("Call state : "); Serial.println(callStateName(callState));
  Serial.print("Mic        : "); Serial.print(mic::isRunning() ? "running" : "stopped");
  Serial.print(", "); Serial.print(mic::available()); Serial.print(" samples queued, overruns ");
  Serial.println(mic::overruns());
  Serial.print("Stream     : "); Serial.print(streamStateName(stream::state()));
  Serial.print(", session "); Serial.print(stream::sessionId());
  Serial.print(", sent "); Serial.print(stream::bytesSent()); Serial.print(" B, dropped frames ");
  Serial.println(stream::framesDropped());
  Serial.print("Wi-Fi      : "); Serial.print(wifiStatusName(WiFi.status()));
  if (WiFi.status() == WL_CONNECTED) { Serial.print(" "); Serial.print(WiFi.localIP()); }
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
  if (command == "status") {
    printStatus();
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

  mic::begin();
  stream::begin(BACKEND_HOST, BACKEND_PORT, BACKEND_WS_PATH, DEVICE_NAME, TRANSCRIPTION_LANGUAGE);
  stream::onTranscript(onTranscriptReceived);
  stream::onStatus(onStreamStatus);
  stream::onAdvisor(onAdvisorReceived);

  // Kick off Wi-Fi in the background so the first call does not have to wait for it.
  if (strlen(WIFI_SSID) > 0) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  }

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
  stream::loop();
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
