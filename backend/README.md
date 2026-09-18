# tahti-fnol-transcript

Spring Boot 3.5 / Java 25 / Spring AI 1.1 backend that receives 16 kHz PCM
audio from the Wio Terminal over a WebSocket, stores it as WAV, and
transcribes it in Finnish with a speech-to-text deployment in Azure AI
Foundry (default `gpt-4o-mini-transcribe`; `gpt-4o-transcribe` or `whisper`
work too, they share the `/audio/transcriptions` API). Wire contract:
[`../docs/audio-ws-protocol.md`](../docs/audio-ws-protocol.md).

## Run

```bash
export AZURE_OPENAI_API_KEY=...                              # never commit this
export AZURE_TRANSCRIBE_DEPLOYMENT=gpt-4o-mini-transcribe    # only if your deployment name differs
cd backend
mvn spring-boot:run
```

Deploying the model in AI Foundry: **Models + endpoints → Deploy base model →
`gpt-4o-mini-transcribe` → Default settings** (Global Standard). Whisper needs a
regional *Standard* deployment, for which new subscriptions often have zero
quota in `swedencentral`.

Listens on `ws://0.0.0.0:8090/ws/audio` (`SERVER_PORT` to change; 8080 is
usually taken by the tahti Docker stack). Recordings land in `./recordings/`.

Run without Azure (store audio only, no transcription):

```bash
AZURE_OPENAI_API_KEY=x SPRING_AI_TRANSCRIPTION=none mvn spring-boot:run
```

## What you see in the log

```
[wio-1a2b3c4d-0001] capture started: device=wio-terminal language=fi format=PcmFormat[...] dir=recordings/20260918-150501-wio-1a2b3c4d-0001
[wio-1a2b3c4d-0001] segment 1 stored (4812 ms, 153984 bytes, transcribe command) -> recordings/.../segment-001.wav
Transcription of segment-001.wav done in 1830 ms
[wio-1a2b3c4d-0001] TRANSCRIPT segment 1 (fi): Hei, haluaisin ilmoittaa autovahingosta.
[wio-1a2b3c4d-0001] capture stopped: 1 segments, 9130 ms total -> recordings/.../session.wav
```

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `app.audio.storage-dir` | `recordings` | Where WAV files are written |
| `app.audio.auto-segment-seconds` | `0` | `N>0` cuts and transcribes every N s without a `transcribe` command (near-live captions) |
| `app.audio.max-segment-seconds` | `600` | Safety cap per segment |
| `app.audio.min-transcribe-millis` | `400` | Shorter segments are stored but not sent to the model |
| `app.transcription.language` | `fi` | Forced transcription language (avoids auto-detect drift) |
| `app.transcription.prompt` | insurance vocabulary | Bias prompt for domain terms, Finnish place names and the henkilötunnus format |
| `spring.ai.azure.openai.audio.transcription.options.deployment-name` | `gpt-4o-mini-transcribe` | Speech-to-text deployment in AI Foundry (`AZURE_TRANSCRIBE_DEPLOYMENT`) |

Azure note: Spring AI uses the classic Azure OpenAI audio API
(`<resource>.cognitiveservices.azure.com`), which is the surface that
supports transcription; the newer Foundry `/openai/v1` endpoint does not yet.

## Design

- `ws/AudioStreamHandler` – one connection = one session; all per-session
  work is serialised on a virtual thread owned by that session, transcription
  calls run on further virtual threads so ingest never blocks.
- `audio/CaptureSession` – in-memory current segment + streaming raw PCM
  file for the full call; `AudioStorage`/`WavWriter` produce the WAV files.
- `transcription/TranscriptionService` – wraps
  `AzureOpenAiAudioTranscriptionModel`; degrades to store-only if the model
  bean is absent.

Later phase: feed the transcript to the `gpt-5.4` chat deployment with MCP
tools to extract structured FNOL data.
