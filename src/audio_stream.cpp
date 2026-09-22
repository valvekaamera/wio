#include "audio_stream.h"

#include <ArduinoJson.h>
#include <WebSocketsClient.h>
#include <rpcWiFi.h>

#include "mic_capture.h"

namespace {

constexpr size_t kFrameSamples = 1024;                 // 64 ms at 16 kHz
constexpr size_t kFrameBytes = kFrameSamples * sizeof(int16_t);
constexpr uint8_t kMaxFramesPerLoop = 4;
constexpr unsigned long kStopAckTimeoutMs = 2000;
constexpr unsigned long kReconnectIntervalMs = 2000;

WebSocketsClient ws;
stream::State currentState = stream::State::Idle;
stream::TranscriptCallback transcriptCb = nullptr;
stream::StatusCallback statusCb = nullptr;
stream::AdvisorCallback advisorCb = nullptr;

const char* cfgHost = "";
uint16_t cfgPort = 0;
const char* cfgPath = "/";
const char* cfgDevice = "device";
const char* cfgLanguage = "fi";

String currentSessionId;
uint16_t sessionCounter = 0;
uint32_t sentBytes = 0;
uint32_t droppedFrames = 0;
unsigned long stopRequestedAt = 0;
bool startSent = false;

// 14 bytes reserved so the library can prepend the frame header in place (headerToPayload=true).
uint8_t txBuf[WEBSOCKETS_MAX_HEADER_SIZE + kFrameBytes];

void setState(stream::State next) {
  if (currentState == next) return;
  currentState = next;
  if (statusCb) statusCb(next);
}

String deviceSerialSuffix() {
  // SAMD51 128-bit serial number, last word.
  const uint32_t word3 = *reinterpret_cast<volatile uint32_t*>(0x00806018);
  char buf[9];
  snprintf(buf, sizeof(buf), "%08lx", static_cast<unsigned long>(word3));
  return String(buf);
}

void sendJson(const JsonDocument& doc) {
  String out;
  serializeJson(doc, out);
  ws.sendTXT(out);
  Serial.print("[ws] -> ");
  Serial.println(out);
}

void sendStart() {
  JsonDocument doc;
  doc["type"] = "start";
  doc["sessionId"] = currentSessionId;
  doc["device"] = cfgDevice;
  doc["language"] = cfgLanguage;
  JsonObject fmt = doc["format"].to<JsonObject>();
  fmt["encoding"] = "pcm_s16le";
  fmt["sampleRate"] = mic::kSampleRate;
  fmt["channels"] = 1;
  sendJson(doc);
  startSent = true;
}

void handleServerText(uint8_t* payload, size_t length) {
  JsonDocument doc;
  const DeserializationError err = deserializeJson(doc, payload, length);
  if (err) {
    Serial.print("[ws] <- (unparseable) ");
    Serial.write(payload, length);
    Serial.println();
    return;
  }
  const char* type = doc["type"] | "";
  Serial.print("[ws] <- ");
  Serial.write(payload, length);
  Serial.println();

  if (strcmp(type, "transcript") == 0) {
    const int segment = doc["segment"] | 0;
    const char* text = doc["text"] | "";
    Serial.print("[transcript #");
    Serial.print(segment);
    Serial.print("] ");
    Serial.println(text);
    if (transcriptCb) transcriptCb(segment, text);
  } else if (strcmp(type, "advisor") == 0) {
    const char* status = doc["status"] | "";
    const char* message = doc["message"] | "";
    JsonArrayConst questions = doc["questions"].as<JsonArrayConst>();
    const int count = questions.isNull() ? 0 : static_cast<int>(questions.size());
    Serial.print("[advisor] ");
    Serial.print(status);
    Serial.print(": ");
    Serial.println(message);
    for (JsonVariantConst q : questions) {
      Serial.print("  - ");
      Serial.println(q.as<const char*>());
    }
    stream::AdvisorStatus st = stream::AdvisorStatus::Error;
    if (strcmp(status, "WORKING") == 0) st = stream::AdvisorStatus::Working;
    else if (strcmp(status, "VALMIS_KORVAUSRATKAISUUN") == 0) st = stream::AdvisorStatus::Ready;
    else if (strcmp(status, "LISAKYSYMYKSET") == 0) st = stream::AdvisorStatus::Questions;
    const char* first = count > 0 ? (questions[0] | "") : "";
    if (advisorCb) advisorCb(st, message, count, first);
  } else if (strcmp(type, "stopped") == 0) {
    if (currentState == stream::State::Stopping) {
      ws.disconnect();
      setState(stream::State::Idle);
    }
  } else if (strcmp(type, "error") == 0) {
    Serial.print("[ws] server error: ");
    Serial.println(doc["message"] | "");
  }
}

void wsEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      Serial.print("[ws] connected to ");
      Serial.println(reinterpret_cast<const char*>(payload));
      if (currentState == stream::State::Connecting || currentState == stream::State::Streaming) {
        sendStart();
        setState(stream::State::Streaming);
      }
      break;
    case WStype_DISCONNECTED:
      Serial.println("[ws] disconnected");
      startSent = false;
      if (currentState == stream::State::Streaming) {
        setState(stream::State::Connecting);   // library will retry; start is re-sent on connect
      } else if (currentState == stream::State::Stopping) {
        setState(stream::State::Idle);
      }
      break;
    case WStype_TEXT:
      handleServerText(payload, length);
      break;
    case WStype_ERROR:
      Serial.println("[ws] error");
      break;
    default:
      break;
  }
}

void drainMic() {
  const bool canSend = currentState == stream::State::Streaming && ws.isConnected() && startSent;
  int16_t* samples = reinterpret_cast<int16_t*>(txBuf + WEBSOCKETS_MAX_HEADER_SIZE);
  for (uint8_t i = 0; i < kMaxFramesPerLoop && mic::available() >= kFrameSamples; ++i) {
    const size_t n = mic::read(samples, kFrameSamples);
    if (n == 0) break;
    if (!canSend) {
      droppedFrames++;
      continue;
    }
    // Client frames are masked in place by the library, so txBuf is scratch after this call.
    if (ws.sendBIN(txBuf, n * sizeof(int16_t), true)) {
      sentBytes += n * sizeof(int16_t);
    } else {
      droppedFrames++;
    }
  }
}

}  // namespace

void stream::begin(const char* host, uint16_t port, const char* path, const char* device, const char* language) {
  cfgHost = host;
  cfgPort = port;
  cfgPath = path;
  cfgDevice = device;
  cfgLanguage = language;
  ws.onEvent(wsEvent);
  ws.setReconnectInterval(kReconnectIntervalMs);
}

void stream::onTranscript(TranscriptCallback cb) { transcriptCb = cb; }
void stream::onStatus(StatusCallback cb) { statusCb = cb; }
void stream::onAdvisor(AdvisorCallback cb) { advisorCb = cb; }

void stream::startSession() {
  if (currentState != State::Idle) return;
  sessionCounter++;
  char id[48];
  snprintf(id, sizeof(id), "wio-%s-%04u", deviceSerialSuffix().c_str(), sessionCounter);
  currentSessionId = id;
  sentBytes = 0;
  droppedFrames = 0;
  startSent = false;
  Serial.print("[ws] opening ws://");
  Serial.print(cfgHost);
  Serial.print(":");
  Serial.print(cfgPort);
  Serial.print(cfgPath);
  Serial.print(" session ");
  Serial.println(currentSessionId);
  setState(State::Connecting);
  ws.begin(cfgHost, cfgPort, cfgPath);
}

void stream::requestTranscribe() {
  if (currentState != State::Streaming || !ws.isConnected()) {
    Serial.println("[ws] transcribe ignored - not streaming");
    return;
  }
  JsonDocument doc;
  doc["type"] = "transcribe";
  sendJson(doc);
}

void stream::stopSession() {
  if (currentState == State::Idle) return;
  if (ws.isConnected() && startSent) {
    // Push whatever is still in the ring before the stop marker.
    drainMic();
    JsonDocument doc;
    doc["type"] = "stop";
    sendJson(doc);
    stopRequestedAt = millis();
    setState(State::Stopping);
  } else {
    ws.disconnect();
    setState(State::Idle);
  }
}

void stream::loop() {
  if (currentState == State::Idle) return;   // not calling ws.loop() suppresses auto-reconnect
  ws.loop();
  if (currentState == State::Streaming || currentState == State::Connecting) {
    drainMic();
  } else if (currentState == State::Stopping && millis() - stopRequestedAt > kStopAckTimeoutMs) {
    Serial.println("[ws] no 'stopped' ack - closing anyway");
    ws.disconnect();
    setState(State::Idle);
  }
}

stream::State stream::state() { return currentState; }
bool stream::isConnected() { return ws.isConnected(); }
const String& stream::sessionId() { return currentSessionId; }
uint32_t stream::bytesSent() { return sentBytes; }
uint32_t stream::framesDropped() { return droppedFrames; }
const char* stream::host() { return cfgHost; }
uint16_t stream::port() { return cfgPort; }
