package ee.evitec.tahti.fnol.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.evitec.tahti.fnol.agent.AdvisorDecision;
import ee.evitec.tahti.fnol.agent.ClaimAdvisor;
import ee.evitec.tahti.fnol.agent.FnolCase;
import ee.evitec.tahti.fnol.audio.AudioStorage;
import ee.evitec.tahti.fnol.audio.CaptureSession;
import ee.evitec.tahti.fnol.audio.PcmFormat;
import ee.evitec.tahti.fnol.audio.WavWriter;
import ee.evitec.tahti.fnol.config.AudioProperties;
import ee.evitec.tahti.fnol.transcription.TranscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * One WebSocket connection = one capture session = one FNOL call. All ingest work for a
 * session runs on that session's own single-thread executor backed by a virtual thread, so
 * the container thread is never blocked and per-session state needs no locking.
 * Transcription runs on a shared virtual-thread pool; the claim-advisor rounds run on a
 * second per-session serial executor so rounds stay in segment order and the final summary
 * is printed after the last round, without ever stalling audio ingest.
 */
@Component
public class AudioStreamHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamHandler.class);
    private static final AtomicLong SESSION_SEQ = new AtomicLong();

    private final ObjectMapper objectMapper;
    private final AudioProperties audioProperties;
    private final AudioStorage storage;
    private final TranscriptionService transcription;
    private final ClaimAdvisor advisor;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService transcriptionPool = Executors.newVirtualThreadPerTaskExecutor();

    public AudioStreamHandler(ObjectMapper objectMapper, AudioProperties audioProperties,
                              AudioStorage storage, TranscriptionService transcription, ClaimAdvisor advisor) {
        this.objectMapper = objectMapper;
        this.audioProperties = audioProperties;
        this.storage = storage;
        this.transcription = transcription;
        this.advisor = advisor;
    }

    /** Per-connection wiring: thread-safe socket wrapper, serial executors, capture + case state. */
    private static final class Connection {
        final WebSocketSession socket;
        final ExecutorService executor;          // ingest + control messages
        final ExecutorService advisorExecutor;   // transcript -> advisor rounds -> summary, in order
        volatile CaptureSession capture;         // null until "start"
        volatile FnolCase fnol;                  // created with the capture
        long droppedBytesBeforeStart;

        Connection(WebSocketSession raw) {
            this.socket = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
            this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-" + raw.getId()).factory());
            this.advisorExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("advisor-" + raw.getId()).factory());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        connections.put(session.getId(), new Connection(session));
        log.info("WebSocket connected: {} from {}", session.getId(), session.getRemoteAddress());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection conn = connections.remove(session.getId());
        if (conn == null) return;
        log.info("WebSocket closed: {} ({})", session.getId(), status);
        conn.executor.execute(() -> {
            CaptureSession capture = conn.capture;
            if (capture != null && !capture.stopped()) {
                log.info("[{}] socket closed without 'stop' - finalising implicitly", capture.sessionId());
                stopCapture(conn, capture);
            }
            // Queued advisor rounds and the summary still complete; no new work is accepted.
            conn.advisorExecutor.shutdown();
        });
        conn.executor.shutdown();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error on {}: {}", session.getId(), exception.toString());
    }

    // ---------------------------------------------------------------- inbound

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection conn = connections.get(session.getId());
        if (conn == null) return;
        String payload = message.getPayload();
        conn.executor.execute(() -> {
            try {
                ClientMessage msg = objectMapper.readValue(payload, ClientMessage.class);
                if (msg.type() == null) {
                    sendError(conn, "missing 'type'");
                    return;
                }
                switch (msg.type()) {
                    case ClientMessage.START -> startCapture(conn, msg);
                    case ClientMessage.TRANSCRIBE -> requireCapture(conn, c -> cutAndTranscribe(conn, c, "transcribe command", true));
                    case ClientMessage.STOP -> requireCapture(conn, c -> stopCapture(conn, c));
                    default -> sendError(conn, "unknown type: " + msg.type());
                }
            } catch (Exception e) {
                log.warn("Bad control message on {}: {}", session.getId(), e.toString());
                sendError(conn, "bad control message: " + e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection conn = connections.get(session.getId());
        if (conn == null) return;
        ByteBuffer buffer = message.getPayload();
        byte[] pcm = new byte[buffer.remaining()];
        buffer.get(pcm);
        conn.executor.execute(() -> {
            CaptureSession capture = conn.capture;
            if (capture == null || capture.stopped()) {
                conn.droppedBytesBeforeStart += pcm.length;
                return;
            }
            try {
                capture.append(pcm);
                maybeAutoCut(conn, capture);
            } catch (IOException e) {
                log.error("[{}] failed to store audio: {}", capture.sessionId(), e.toString());
                sendError(conn, "storage failure: " + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- commands

    private void startCapture(Connection conn, ClientMessage msg) throws IOException {
        if (conn.capture != null && !conn.capture.stopped()) {
            sendError(conn, "session already started");
            return;
        }
        PcmFormat format = (msg.format() == null ? PcmFormat.defaultFormat(audioProperties.defaultSampleRate())
                : msg.format()).withDefaults(audioProperties.defaultSampleRate());
        try {
            format.validate();
        } catch (IllegalArgumentException e) {
            sendError(conn, e.getMessage());
            return;
        }
        String sessionId = msg.sessionId() == null || msg.sessionId().isBlank()
                ? "srv-" + Long.toHexString(System.currentTimeMillis()) + "-" + SESSION_SEQ.incrementAndGet()
                : msg.sessionId();
        Path dir = storage.createSessionDir(sessionId);
        var capture = new CaptureSession(sessionId, msg.device(), msg.language(), format, dir, storage.openRawPcm(dir));
        conn.capture = capture;
        conn.fnol = new FnolCase(sessionId, msg.device() == null ? "unknown" : msg.device());
        if (conn.droppedBytesBeforeStart > 0) {
            log.warn("[{}] {} audio bytes arrived before 'start' and were dropped", sessionId, conn.droppedBytesBeforeStart);
        }
        log.info("[{}] capture started: device={} language={} format={} dir={} advisor={}",
                sessionId, msg.device(), msg.language(), format, dir, advisor.isEnabled() ? "on" : "off");
        send(conn, event("ready", sessionId, Map.of(
                "format", format,
                "transcriptionEnabled", transcription.isEnabled(),
                "advisorEnabled", advisor.isEnabled())));
    }

    /**
     * Cuts the pending audio into a segment, stores it, transcribes it and (unless the call
     * is being ended) feeds the transcript to the claim advisor.
     */
    private void cutAndTranscribe(Connection conn, CaptureSession capture, String reason, boolean runAdvisor) {
        byte[] pcm = capture.cutSegment();
        if (pcm.length == 0) {
            log.info("[{}] {} - nothing captured since last cut", capture.sessionId(), reason);
            return;
        }
        int segmentNo = capture.segmentCount();
        long durationMs = capture.format().durationMillis(pcm.length);
        Path file;
        try {
            file = storage.writeSegment(capture.dir(), segmentNo, capture.format(), pcm);
        } catch (IOException e) {
            log.error("[{}] failed to write segment {}: {}", capture.sessionId(), segmentNo, e.toString());
            sendError(conn, "segment storage failure: " + e.getMessage());
            return;
        }
        log.info("[{}] segment {} stored ({} ms, {} bytes, {}) -> {}",
                capture.sessionId(), segmentNo, durationMs, pcm.length, reason, file);
        send(conn, event("segment", capture.sessionId(), Map.of(
                "segment", segmentNo, "durationMs", durationMs, "file", file.toString())));

        if (durationMs < audioProperties.minTranscribeMillis()) {
            log.info("[{}] segment {} shorter than {} ms - not transcribed",
                    capture.sessionId(), segmentNo, audioProperties.minTranscribeMillis());
            return;
        }
        byte[] wav = WavWriter.toWav(capture.format(), pcm);
        String fileName = file.getFileName().toString();
        String language = capture.language() == null ? "fi" : capture.language();
        FnolCase fnol = conn.fnol;

        CompletableFuture<Optional<String>> transcript = CompletableFuture.supplyAsync(() -> {
            try {
                Optional<String> text = transcription.transcribe(wav, fileName, language);
                text.ifPresentOrElse(t -> {
                    log.info("[{}] TRANSCRIPT segment {} ({}): {}", capture.sessionId(), segmentNo, language, t);
                    send(conn, event("transcript", capture.sessionId(), Map.of(
                            "segment", segmentNo, "language", language, "text", t)));
                    if (runAdvisor && advisor.isEnabled()) {
                        send(conn, advisorEvent(capture.sessionId(), "WORKING", "Analysoidaan...", null, segmentNo, null));
                    }
                }, () -> log.info("[{}] segment {} produced no transcript", capture.sessionId(), segmentNo));
                return text;
            } catch (Exception e) {
                log.error("[{}] transcription of segment {} failed: {}", capture.sessionId(), segmentNo, e.toString());
                sendError(conn, "transcription failed for segment " + segmentNo + ": " + e.getMessage());
                return Optional.empty();
            }
        }, transcriptionPool);

        // Serialised per session: rounds run in segment order and the summary waits for them.
        submitAdvisor(conn, () -> {
            Optional<String> text = transcript.join();
            if (text.isEmpty() || fnol == null) return;
            if (runAdvisor && advisor.isEnabled()) {
                runAdvisorRound(conn, fnol, segmentNo, text.get());
            } else {
                fnol.addTranscript(segmentNo, text.get());   // e.g. trailing audio after hang-up
            }
        });
    }

    private void runAdvisorRound(Connection conn, FnolCase fnol, int segmentNo, String text) {
        if (fnol.disposed() || fnol.hungUp()) {
            fnol.addTranscript(segmentNo, text);
            return;
        }
        try {
            ClaimAdvisor.RoundResult r = advisor.advance(fnol, segmentNo, text);
            AdvisorDecision d = r.decision();
            String message = r.verdict().endCall()
                    ? advisor.closingPhrase()
                    : "Lisäkysymyksiä: " + d.questions().size();
            send(conn, advisorEvent(fnol.sessionId(), r.verdict().name(), message,
                    r.verdict().endCall() ? null : d.questions(), segmentNo, r));
        } catch (Exception e) {
            log.error("[{}] claim advisor round failed for segment {}: {}", fnol.sessionId(), segmentNo, e.toString(), e);
            fnol.recordError(e.toString());
            send(conn, advisorEvent(fnol.sessionId(), "ERROR", "Neuvoja ei vastannut", null, segmentNo, null));
        }
    }

    private void stopCapture(Connection conn, CaptureSession capture) {
        if (capture.stopped()) return;
        FnolCase fnol = conn.fnol;
        if (fnol != null) fnol.markHungUp();        // decisions arriving after this keep status KESKEN
        if (capture.pendingSegmentBytes() > 0) {
            cutAndTranscribe(conn, capture, "stop command", false);
        }
        try {
            capture.markStopped();
            Path file = storage.finalizeSession(capture.dir(), capture.format());
            log.info("[{}] capture stopped: {} segments, {} ms total -> {}",
                    capture.sessionId(), capture.segmentCount(), capture.totalDurationMillis(), file);
            send(conn, event("stopped", capture.sessionId(), Map.of(
                    "segments", capture.segmentCount(),
                    "durationMs", capture.totalDurationMillis(),
                    "file", file.toString())));
        } catch (IOException e) {
            log.error("[{}] failed to finalise session: {}", capture.sessionId(), e.toString());
            sendError(conn, "finalise failure: " + e.getMessage());
        }
        if (fnol != null) {
            conn.fnol = null;                        // a new "start" on this socket begins a fresh case
            submitAdvisor(conn, () -> {              // step 5, after any in-flight round
                advisor.logSummary(fnol);
                fnol.dispose();                      // drop LLM history + cached policy data of this call
            });
        }
    }

    private void maybeAutoCut(Connection conn, CaptureSession capture) {
        int maxBytes = audioProperties.maxSegmentSeconds() * capture.format().bytesPerSecond();
        if (capture.pendingSegmentBytes() >= maxBytes) {
            cutAndTranscribe(conn, capture, "max segment length", true);
            return;
        }
        int auto = audioProperties.autoSegmentSeconds();
        if (auto > 0 && capture.millisSinceLastCut() >= auto * 1000L) {
            cutAndTranscribe(conn, capture, "auto segment", true);
        }
    }

    // ---------------------------------------------------------------- helpers

    private interface CaptureAction {
        void run(CaptureSession capture);
    }

    private void requireCapture(Connection conn, CaptureAction action) {
        CaptureSession capture = conn.capture;
        if (capture == null || capture.stopped()) {
            sendError(conn, "no active session - send 'start' first");
            return;
        }
        action.run(capture);
    }

    private void submitAdvisor(Connection conn, Runnable task) {
        try {
            conn.advisorExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("Advisor executor already shut down for {}", conn.socket.getId());
        }
    }

    private Map<String, Object> event(String type, String sessionId, Map<String, ?> fields) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("sessionId", sessionId);
        map.putAll(fields);
        return map;
    }

    private Map<String, Object> advisorEvent(String sessionId, String status, String message,
                                             java.util.List<String> questions, int segmentNo,
                                             ClaimAdvisor.RoundResult round) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", status);
        fields.put("message", message);
        fields.put("segment", segmentNo);
        if (round != null) {
            fields.put("caseStatus", round.caseStatus().name());
            fields.put("endCall", round.verdict().endCall());
            if (!round.decision().pendingEvidence().isEmpty()) {
                fields.put("pendingEvidence", round.decision().pendingEvidence());
            }
        }
        if (questions != null) fields.put("questions", questions);
        return event("advisor", sessionId, fields);
    }

    private void sendError(Connection conn, String message) {
        CaptureSession capture = conn.capture;
        send(conn, event("error", capture == null ? null : capture.sessionId(), Map.of("message", message)));
    }

    private void send(Connection conn, Map<String, Object> event) {
        if (!conn.socket.isOpen()) return;
        try {
            conn.socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.debug("Could not send {} to {}: {}", event.get("type"), conn.socket.getId(), e.toString());
        }
    }
}
