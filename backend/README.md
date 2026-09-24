# tahti-fnol-transcript

Spring Boot 3.5 / Java 25 / Spring AI 1.1 backend that receives 16 kHz PCM
audio from the Wio Terminal over a WebSocket, stores it as WAV, transcribes
it in Finnish with a speech-to-text deployment in Azure AI Foundry (default
`gpt-4o-mini-transcribe`; `gpt-4o-transcribe` or `whisper` work too, they
share the `/audio/transcriptions` API), and runs a **claim-advisor agent**
(`gpt-5.4` + policy tools) over the transcript to drive the FNOL intake.
Wire contract: [`../docs/audio-ws-protocol.md`](../docs/audio-ws-protocol.md).

## Run

```bash
export AZURE_OPENAI_API_KEY=...                              # never commit this
export AZURE_TRANSCRIBE_DEPLOYMENT=gpt-4o-mini-transcribe    # only if your deployment name differs
export AZURE_CHAT_DEPLOYMENT=gpt-5.4                         # only if your deployment name differs
cd backend
mvn spring-boot:run
```

Listens on `ws://0.0.0.0:8090/ws/audio` (`SERVER_PORT` to change; 8080 is
usually taken by the tahti Docker stack). Recordings land in `./recordings/`.
The policy tools expect `tahti-rest-app` on `:8085` and `pcpc-rest-app` on
`:8080` (see configuration).

Deploying the models in AI Foundry: **Models + endpoints → Deploy base model →
`gpt-4o-mini-transcribe` → Default settings** (Global Standard). Whisper needs a
regional *Standard* deployment, for which new subscriptions often have zero
quota in `swedencentral`.

Run without Azure (store audio only, no transcription, no advisor):

```bash
AZURE_OPENAI_API_KEY=x SPRING_AI_TRANSCRIPTION=none APP_ADVISOR_ENABLED=false mvn spring-boot:run
```

## Claim-advisor loop (FNOL intake)

One capture session = one call = one `FnolCase`. Every `transcribe` (Wio
button **A**) produces a transcript segment and one advisor round:

1. **Extraction** — every round `gpt-5.4` re-reads the whole transcript and
   pulls out hetu, loss date (with the words that state it), caller and
   loss description. Java then validates: the hetu by format + check
   character (STT drops digits often), the loss date only if its evidence
   phrase really occurs in the transcript — so "tänään"/"eilen" work, but a
   silently assumed "today" is rejected.
2. **Policy discovery** — once hetu and date are known the model gets the
   tool-set and walks *insurables → coverages → risks / claim types →
   terms* for the caller, matching the described loss to every candidate
   path (e.g. appliance as Irtaimisto vs. fixture of Huoneisto,
   Rikkoutuminen vs. Putkivuoto). The tools are pinned to the validated
   hetu and date of the current call, and record every element they
   return in that call's `FnolCase`.
3. **Call can end** — the model answers `PUHELU_VALMIS` with an outcome
   (`KORVATTAVA` / `OSITTAIN_KORVATTAVA` / `EI_KORVATTAVA`) and a list of
   proof the caller still has to send (`lisaselvitykset`). Java derives
   the case status from that:

   | Outcome | Pending proof | Verdict / case status |
   | --- | --- | --- |
   | `KORVATTAVA`, `OSITTAIN_KORVATTAVA` | none | `VALMIS_KORVAUSRATKAISUUN` → **VALMIS KORVAUSRATKAISUUN** |
   | `EI_KORVATTAVA` | none | `EI_KORVATTAVA` → **EI KORVATTAVA** |
   | any | yes (or next steps ask for documents) | `ODOTTAA_LISASELVITYKSIA` → **KESKEN** |

   In all three the log shows `=> Sano asiakkaalle: "Kiitos, otamme teihin
   pian yhteyttä." ja lopeta puhelu (B)` and the Wio shows the verdict.
4. **LISAKYSYMYKSET** — something essential is missing or two paths remain.
   A missing hetu or loss date always forces this, with the question added
   if the model forgot it. The log prints the transcript so far and the
   numbered questions; the Wio shows the count and the first question. The
   handler asks, presses **A**, and the next round continues the same
   conversation.
5. **Summary** — on `stop` (button **B**, any time) the `FNOL-YHTEENVETO`
   block is logged with the status derived before hang-up (hanging up
   earlier leaves `KESKEN`), caller, hetu, loss date, matched policy path
   with *Vakuutusnumero, Vakuutusmäärä, Vakuutusmäärän peruste, Omavastuu,
   Omavastuutyyppi, Ehdot* (read from the tool results, claim type before
   risk before coverage; `*` marks a value only the model reported),
   decision proposal, pending proof, questions asked and the transcript.
   The case is then disposed: its LLM conversation and cached policy data
   are dropped, and each round logs how many earlier messages it sees
   (always 0 in round 1).

Design principle: the model brings generic claims-intake competence; every
proprietary fact (which coverages, risks, claim types, terms, deductibles
exist) comes from tool results at runtime; the system prompt is the
protocol between them. The prompt lives in `agent/ClaimAdvisor.java`.

### Tool-set

Same names and arguments as the tahti MCP server, so the prompt is
portable (`agent/tools/`). Exposed as one `ToolCallbackProvider` bean —
adding `spring-ai-starter-mcp-server-webmvc` would publish it to remote MCP
clients unchanged.

| Tool | Source |
| --- | --- |
| `getInsurablesByPolicyholderHetu(hetu, targetdate)` | `/tahti-data/valid_insurables_policyholder/hetu/{hetu}/targetdate/{d}/option/both` |
| `getCoveragesByInsurableOid(oid, targetdate)` | `/tahti-data/valid_coverages_insurable/{oid}/targetdate/{d}/option/both` |
| `getRisksByCoverageOid(oid, targetdate)` | `/tahti-data/valid_risks_coverage/{oid}/targetdate/{d}/option/both` |
| `getEcoveragesByCoverageOid(oid, targetdate)` | `/tahti-data/valid_ecoverages_coverage/{oid}/targetdate/{d}/option/both` |
| `getConstraintTermsByParentOid(oid, targetdate)` | `/tahti-data/valid_terms_parent/{oid}/targetdate/{d}/option/both` |
| `getGeneralTermsByParentOid(oid, targetdate)` | `/tahti-data/valid_gen_terms_parent/{oid}/targetdate/{d}` |
| `getEntityTypesByIds(ids)` | pcpc `/branch/{branch}/entities`, cached, sliced by id |
| `searchEntityTypesByName(query, limit)` | same cache, Finnish name search |

Policy responses are compacted before they reach the model (transient and
`*PreviousValue` attributes, session OIDs and pricing intermediates are
dropped; ~50 % smaller) and every `Type` code is enriched with its Finnish
`TypeName` from the ontology cache, so the model rarely needs the ontology
tools at all. The ~0.5 MB entity catalogue is fetched once and cached for
`app.pcpc.cache-ttl`.

### Text-only entry (replay / other adapters)

```bash
curl -s -X POST localhost:8090/api/fnol/demo1/segments -H 'Content-Type: application/json' \
  -d '{"text":"Tervehdys, olen Ville, hetuni on 090798-921E. Pesukone meni rikki 13.9.2026 ..."}'
curl -s -X POST localhost:8090/api/fnol/demo1/hangup
```

Runs exactly the same rounds as the Wio path, minus audio — handy for
tuning the prompt against stored transcripts.

## What you see in the log

```
[wio-1a2b3c4d-0001] capture started: device=wio-terminal language=fi ... advisor=on
[wio-1a2b3c4d-0001] segment 1 stored (4812 ms, 153984 bytes, transcribe command) -> recordings/.../segment-001.wav
[wio-1a2b3c4d-0001] TRANSCRIPT segment 1 (fi): Tervehdys, olen Ville ja hetuni on 09078-921E ...
[wio-1a2b3c4d-0001] advisor round 1 starts: 0 earlier messages, 0 policy elements known in this call
[wio-1a2b3c4d-0001] extraction: hetu=09078-921E (muoto virheellinen ...), lossDate=2026-09-13 (evidence: '13.09.2026'), caller=Ville, loss=Pesukone rikkoutui ...
[wio-1a2b3c4d-0001] ---- KIERROS 1 (6 s) -> LISAKYSYMYKSET   tila: KESKEN (lisäkysymyksiä avoinna)
Transkriptio tähän mennessä:
  [1] ...
LISÄKYSYMYKSET - kysy asiakkaalta, sitten paina A:
  1. Voisitteko toistaa henkilötunnuksenne numero kerrallaan?
[wio-1a2b3c4d-0001] advisor round 2 starts: 2 earlier messages, 0 policy elements known in this call
[wio-1a2b3c4d-0001] [tool] getInsurablesByPolicyholderHetu(090798-921E, 2026-09-13) -> 2 items, 1319 chars in 1137 ms
[wio-1a2b3c4d-0001] [tool] getCoveragesByInsurableOid(34CC3A72..., 2026-09-13) -> 1 items, 686 chars in 143 ms
...
[wio-1a2b3c4d-0001] ---- KIERROS 2 (21 s) -> VALMIS_KORVAUSRATKAISUUN   tila: VALMIS KORVAUSRATKAISUUN (korvausratkaisu voidaan tehdä)
Vakuutus:
  - VARASTOTIE 1, VANTAA, Irtaimisto / Irtaimiston Tähtiturva / Rikkoutuminen / Esinevahinko
      Vakuutusnumero 991-1870594-001 | Vakuutusmäärä 4390.51 € | Vakuutusmäärän peruste Täysarvo
      Omavastuu 200.00 € | Omavastuutyyppi Kiinteä | Ehdot KO300, YL100
Korvausratkaisuehdotus: KORVATTAVA - ...
=> Sano asiakkaalle: "Kiitos, otamme teihin pian yhteyttä." ja lopeta puhelu (B).
[wio-1a2b3c4d-0001] ================= FNOL-YHTEENVETO =================
Tila: VALMIS KORVAUSRATKAISUUN (korvausratkaisu voidaan tehdä)
...
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
| `spring.ai.azure.openai.audio.transcription.options.deployment-name` | `gpt-4o-mini-transcribe` | Speech-to-text deployment (`AZURE_TRANSCRIBE_DEPLOYMENT`) |
| `spring.ai.azure.openai.chat.options.deployment-name` | `gpt-5.4` | Advisor model (`AZURE_CHAT_DEPLOYMENT`) |
| `app.tahti.base-url` | `http://localhost:8085/tahti-rest-app` | Policy data REST (`TAHTI_REST_URL`); `/tahti-data/...` appended |
| `app.pcpc.base-url` | `http://localhost:8080/pcpc-rest-app` | Ontology REST (`PCPC_REST_URL`) |
| `app.pcpc.branch` | `tahti4devTEST20260921` | Product-model branch (`PCPC_BRANCH`) |
| `app.pcpc.cache-ttl` | `1h` | Entity catalogue cache lifetime |
| `app.advisor.enabled` | `true` | Run the advisor after each transcript (`APP_ADVISOR_ENABLED`) |
| `app.advisor.closing-phrase` | `Kiitos, otamme teihin pian yhteyttä.` | Said when a decision is ready |
| `app.advisor.reasoning-effort` | *(blank)* | gpt-5 `reasoning_effort` for the tool round (`APP_ADVISOR_REASONING`), e.g. `medium` |
| `app.advisor.extraction-reasoning-effort` | *(blank)* | same for the extraction call (`APP_ADVISOR_EXTRACTION_REASONING`), e.g. `low` |
| `app.advisor.max-questions-per-round` | `4` | Cap on follow-up questions per round |

Azure note: Spring AI uses the classic Azure OpenAI API
(`<resource>.cognitiveservices.azure.com`), which is the surface that
supports transcription; the newer Foundry `/openai/v1` endpoint does not yet.

## Design

- `ws/AudioStreamHandler` – one connection = one session; ingest runs on a
  virtual thread owned by that session, transcription on a shared pool,
  and advisor rounds on a second per-session serial executor so rounds stay
  in segment order and the summary prints after the last one.
- `audio/CaptureSession` – in-memory current segment + streaming raw PCM
  file for the full call; `AudioStorage`/`WavWriter` produce the WAV files.
- `transcription/TranscriptionService` – wraps
  `AzureOpenAiAudioTranscriptionModel`; degrades to store-only if the model
  bean is absent.
- `agent/ClaimAdvisor` – the loop above; `FnolCase` holds transcript,
  chat history, validated hetu/date, rounds and status; `Hetu` validates
  the identity code; `AdvisorDecision` is the structured round output.
- `policy/TahtiPolicyClient`, `policy/InsuranceOntology` – REST access and
  response compaction / caching; `agent/tools/*` – the `@Tool` facade.
- `api/FnolTextController` – text-only entry to the same loop.

Privacy: transcripts, hetus and the summary are logged in clear and WAVs are
kept indefinitely under `recordings/`. Fine for the dev branch with test
persons; mask the log and add retention before real callers.
