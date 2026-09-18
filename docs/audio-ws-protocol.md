# Audio streaming WebSocket contract

Endpoint: `ws://<backend-host>:8080/ws/audio`

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
capture continues; subsequent frames start the next segment.

### `stop` — Wio button B

```json
{ "type": "stop" }
```

Finalises the session: any pending (non-empty) segment is transcribed,
the full-session WAV is written, and the server replies with `stopped`.
Either side may then close the socket. Closing the socket without `stop`
is treated as an implicit `stop`.

## Server → client events

```json
{ "type": "ready", "sessionId": "wio-1a2b3c-0007", "format": { "encoding": "pcm_s16le", "sampleRate": 16000, "channels": 1 } }

{ "type": "segment", "sessionId": "...", "segment": 1, "durationMs": 4812, "file": "recordings/20260918-150501-wio-1a2b3c-0007/segment-001.wav" }

{ "type": "transcript", "sessionId": "...", "segment": 1, "language": "fi", "text": "Hei, haluaisin ilmoittaa vahingosta." }

{ "type": "stopped", "sessionId": "...", "segments": 2, "durationMs": 9130, "file": "recordings/20260918-150501-wio-1a2b3c-0007/session.wav" }

{ "type": "error", "sessionId": "...", "message": "unsupported encoding: opus" }
```

`transcript` may arrive well after `segment` (Whisper is batch-only) and
may arrive after `stopped` if the last segment was still in flight.
Clients must not assume ordering between `segment`/`transcript` events and
binary acknowledgement; there is no per-frame ack.

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
