// WebSocket audio session to the FNOL backend (see docs/audio-ws-protocol.md).
//
//   startSession()      -> connect, send {"type":"start"}, then stream PCM from mic ring
//   requestTranscribe() -> {"type":"transcribe"}
//   stopSession()       -> {"type":"stop"}, wait for "stopped" (or timeout), disconnect
//
// loop() must be called from the main loop; it is non-blocking.
#pragma once
#include <Arduino.h>

namespace stream {

enum class State { Idle, Connecting, Streaming, Stopping };

// Claim-advisor verdict for the latest segment (server event "advisor").
// Ready / NotCovered / AwaitingEvidence all mean "say the closing phrase and hang up";
// only Ready leaves the case VALMIS KORVAUSRATKAISUUN, AwaitingEvidence keeps it KESKEN.
enum class AdvisorStatus { Working, Ready, NotCovered, AwaitingEvidence, Questions, Error };

using TranscriptCallback = void (*)(int segment, const char* text);
using StatusCallback = void (*)(State state);
// message: closing phrase / short headline; firstQuestion: first "lisäkysymys" or "" (all are on serial + server log)
using AdvisorCallback = void (*)(AdvisorStatus status, const char* message, int questionCount, const char* firstQuestion);

void begin(const char* host, uint16_t port, const char* path, const char* device, const char* language);
void onTranscript(TranscriptCallback cb);
void onStatus(StatusCallback cb);
void onAdvisor(AdvisorCallback cb);

void startSession();
void requestTranscribe();
void stopSession();
void loop();

State state();
bool isConnected();
const String& sessionId();
uint32_t bytesSent();
uint32_t framesDropped();     // audio frames discarded because the socket was not connected
const char* host();
uint16_t port();

}  // namespace stream
