package ee.evitec.tahti.fnol.ws;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * One WebSocket connection = one capture session. All work for a session runs on that
 * session's own single-thread executor backed by a virtual thread, so the container
 * thread is never blocked and per-session state needs no locking. Transcription calls
 * run on separate virtual threads so audio ingest continues while Whisper works.
 */
@Component
public class AudioStreamHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamHandler.class);
    private static final AtomicLong SESSION_SEQ = new AtomicLong();

    private final ObjectMapper objectMapper;
    private final AudioProperties audioProperties;
    private final AudioStorage storage;
    private final TranscriptionService transcription;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ExecutorService transcriptionPool = Executors.newVirtualThreadPerTaskExecutor();

    public AudioStreamHandler(ObjectMapper objectMapper, AudioProperties audioProperties,
                              AudioStorage storage, TranscriptionService transcription) {
        this.objectMapper = objectMapper;
        this.audioProperties = audioProperties;
        this.storage = storage;
        this.transcription = transcription;
    }

    /** Per-connection wiring: thread-safe socket wrapper, serial executor, capture state. */
    private static final class Connection {
        final WebSocketSession socket;
        final ExecutorService executor;
        volatile CaptureSession capture;   // null until "start"
        long droppedBytesBeforeStart;

        Connection(WebSocketSession raw) {
            this.socket = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
            this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("ws-" + raw.getId()).factory());
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
                    case ClientMessage.TRANSCRIBE -> requireCapture(conn, c -> cutAndTranscribe(conn, c, "transcribe command"));
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
        if (conn.droppedBytesBeforeStart > 0) {
            log.warn("[{}] {} audio bytes arrived before 'start' and were dropped", sessionId, conn.droppedBytesBeforeStart);
        }
        log.info("[{}] capture started: device={} language={} format={} dir={}",
                sessionId, msg.device(), msg.language(), format, dir);
        send(conn, event("ready", sessionId, Map.of("format", format, "transcriptionEnabled", transcription.isEnabled())));
    }

    private void cutAndTranscribe(Connection conn, CaptureSession capture, String reason) {
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
        String language = capture.language();
        transcriptionPool.execute(() -> {
            try {
                transcription.transcribe(wav, fileName, language).ifPresentOrElse(text -> {
                    log.info("[{}] TRANSCRIPT segment {} ({}): {}", capture.sessionId(), segmentNo, language == null ? "fi" : language, text);
                    send(conn, event("transcript", capture.sessionId(), Map.of(
                            "segment", segmentNo, "language", language == null ? "fi" : language, "text", text)));
                }, () -> log.info("[{}] segment {} produced no transcript", capture.sessionId(), segmentNo));
            } catch (Exception e) {
                log.error("[{}] transcription of segment {} failed: {}", capture.sessionId(), segmentNo, e.toString());
                sendError(conn, "transcription failed for segment " + segmentNo + ": " + e.getMessage());
            }
        });
    }

    private void stopCapture(Connection conn, CaptureSession capture) {
        if (capture.stopped()) return;
        if (capture.pendingSegmentBytes() > 0) {
            cutAndTranscribe(conn, capture, "stop command");
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
    }

    private void maybeAutoCut(Connection conn, CaptureSession capture) {
        int maxBytes = audioProperties.maxSegmentSeconds() * capture.format().bytesPerSecond();
        if (capture.pendingSegmentBytes() >= maxBytes) {
            cutAndTranscribe(conn, capture, "max segment length");
            return;
        }
        int auto = audioProperties.autoSegmentSeconds();
        if (auto > 0 && capture.millisSinceLastCut() >= auto * 1000L) {
            cutAndTranscribe(conn, capture, "auto segment");
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

    private Map<String, Object> event(String type, String sessionId, Map<String, ?> fields) {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("sessionId", sessionId);
        map.putAll(fields);
        return map;
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
