# Audio streaming WebSocket contract

Endpoint: `ws://<backend-host>:8090/ws/audio`

The contract is deliberately client-agnostic. The Wio Terminal, a browser
tab (`AudioWorklet` → PCM), or a telephony adapter can all act as the client
without changes to the backend pipeline. One WebSocket connection = one
capture session (one phone call).

## Framing

| Direction | WebSocket frame | Content |
| --- | --- | --- |
| client → server | **text** | JSON control message (`type` field is mandatory) |
| client → server | **binary** | Raw PCM audio in the format announced in `start`; any length, but a whole number of samples |
| server → client | **text** | JSON event (`type` field is mandatory) |

Binary frames are never wrapped in JSON. Base64 is not used.

## Client → server messages

### `start` — must be the first message

```json
{
  "type": "start",
  "sessionId": "wio-1a2b3c-0007",
  "device": "wio-terminal",
  "language": "fi",
  "format": { "encoding": "pcm_s16le", "sampleRate": 16000, "channels": 1 }
}
```

- `sessionId` optional; the server generates one if missing or empty.
- `language` optional ISO-639-1; server default is `fi`.
- `format` optional; defaults shown above. Only `pcm_s16le`, mono is
  accepted today. Binary frames before `start` are dropped.

### binary frames — audio

Little-endian signed 16-bit mono PCM at the announced sample rate.
The Wio sends ~64 ms per frame (1024 samples = 2048 bytes); a browser
could send larger frames. Frame size carries no meaning to the server.

### `transcribe` — Wio button A

```json
{ "type": "transcribe" }
```

Cuts the audio received since the previous cut (or since `start`) into a
segment, stores it as WAV, and sends it to the transcription model. Audio
capture continues; subsequent frames start the next segment. When the claim
advisor is enabled the transcript is then fed to the advisor loop and an
`advisor` event follows (see below).

### `stop` — Wio button B

```json
{ "type": "stop" }
```

Finalises the session: any pending (non-empty) segment is transcribed
(but not fed to the advisor — the handler has hung up), the full-session
WAV is written, and the server replies with `stopped`. The FNOL call
summary is then printed in the server log with status `KESKEN` or
`VALMIS KORVAUSRATKAISUUN`. Either side may then close the socket. Closing
the socket without `stop` is treated as an implicit `stop`.

## Server → client events

```json
{ "type": "ready", "sessionId": "wio-1a2b3c-0007", "format": { "encoding": "pcm_s16le", "sampleRate": 16000, "channels": 1 }, "transcriptionEnabled": true, "advisorEnabled": true }

{ "type": "segment", "sessionId": "...", "segment": 1, "durationMs": 4812, "file": "recordings/20260918-150501-wio-1a2b3c-0007/segment-001.wav" }

{ "type": "transcript", "sessionId": "...", "segment": 1, "language": "fi", "text": "Hei, haluaisin ilmoittaa vahingosta." }

{ "type": "advisor", "sessionId": "...", "segment": 1, "status": "WORKING", "message": "Analysoidaan..." }

{ "type": "advisor", "sessionId": "...", "segment": 1, "status": "LISAKYSYMYKSET", "message": "Lisäkysymyksiä: 2",
  "caseStatus": "KESKEN", "endCall": false,
  "questions": ["Voisitteko toistaa henkilötunnuksenne numero kerrallaan?", "Minä päivänä vahinko tapahtui?"] }

{ "type": "advisor", "sessionId": "...", "segment": 2, "status": "VALMIS_KORVAUSRATKAISUUN", "message": "Kiitos, otamme teihin pian yhteyttä.",
  "caseStatus": "VALMIS_KORVAUSRATKAISUUN", "endCall": true }

{ "type": "advisor", "sessionId": "...", "segment": 2, "status": "ODOTTAA_LISASELVITYKSIA", "message": "Kiitos, otamme teihin pian yhteyttä.",
  "caseStatus": "KESKEN", "endCall": true, "pendingEvidence": ["Eläinlääkärin todistus kuolemasta"] }

{ "type": "advisor", "sessionId": "...", "segment": 2, "status": "EI_KORVATTAVA", "message": "Kiitos, otamme teihin pian yhteyttä.",
  "caseStatus": "EI_KORVATTAVA", "endCall": true }

{ "type": "advisor", "sessionId": "...", "segment": 2, "status": "ERROR", "message": "Neuvoja ei vastannut" }

{ "type": "stopped", "sessionId": "...", "segments": 2, "durationMs": 9130, "file": "recordings/20260918-150501-wio-1a2b3c-0007/session.wav" }

{ "type": "error", "sessionId": "...", "message": "unsupported encoding: opus" }
```

`transcript` may arrive well after `segment` (transcription is batch-only)
and may arrive after `stopped` if the last segment was still in flight.
Clients must not assume ordering between `segment`/`transcript` events and
binary acknowledgement; there is no per-frame ack.

### `advisor` — claim-advisor verdict for a segment

Sent only when the advisor is enabled (`ready.advisorEnabled`). `WORKING`
follows every `transcript` immediately; the verdict arrives seconds to tens
of seconds later:

| `status` | `caseStatus` | Meaning | What the handler does |
| --- | --- | --- | --- |
| `WORKING` | – | LLM round in progress | wait |
| `LISAKYSYMYKSET` | `KESKEN` | essential facts missing / ambiguous (always when hetu or loss date is missing); `questions` lists them (also in the server log with the transcript so far) | ask the caller, then send `transcribe` again |
| `VALMIS_KORVAUSRATKAISUUN` | `VALMIS_KORVAUSRATKAISUUN` | compensable, policy match established, nothing pending | say `message`, send `stop` |
| `EI_KORVATTAVA` | `EI_KORVATTAVA` | no valid coverage responds to the loss | say `message`, send `stop` |
| `ODOTTAA_LISASELVITYKSIA` | `KESKEN` | all phone facts collected, but the caller must send proof (`pendingEvidence`) before a decision | say `message`, send `stop`; case stays open |
| `ERROR` | – | round failed (model/tool error); case stays `KESKEN` | retry with `transcribe` or hang up |

`caseStatus` is derived by the server from the advisor's structured answer,
never taken from model free text, so the summary cannot contradict itself.
Hanging up before an `endCall: true` verdict leaves the case `KESKEN`.
After `stop` the case (LLM conversation and cached policy data) is
discarded; the next `start` begins with an empty context.

Rounds are serialised per session in segment order, and later rounds see
the whole conversation, so a client may keep sending `transcribe` until it
gets `VALMIS_KORVAUSRATKAISUUN`.

## Text-only entry (no audio)

The same advisor loop is reachable over HTTP for adapters that already
have text, and for replaying transcripts during development:

```
POST   /api/fnol/{caseId}/segments   {"text": "..."}   -> decision JSON for this round
POST   /api/fnol/{caseId}/hangup                       -> prints the FNOL summary, returns status, ends the case
DELETE /api/fnol/{caseId}
```

## Storage layout on the server

```
recordings/
  20260918-150501-wio-1a2b3c-0007/
    segment-001.wav      # audio sent to the model for transcript #1
    segment-002.wav
    session.wav          # everything received in the session
```

## Optional automatic chunking

`app.audio.auto-segment-seconds=N` (default `0` = off) makes the server cut
and transcribe a segment every N seconds without a `transcribe` message.
This is the "rapid chunking" mode for near-live captions; the Wio flow uses
the explicit `transcribe` command instead. Both can be combined.
