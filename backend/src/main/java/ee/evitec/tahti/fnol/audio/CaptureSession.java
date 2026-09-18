package ee.evitec.tahti.fnol.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Mutable state of one capture session (one call). Not thread-safe by itself:
 * the owning handler serialises all access through the session's executor.
 */
public final class CaptureSession {

    private final String sessionId;
    private final String device;
    private final String language;
    private final PcmFormat format;
    private final Path dir;
    private final OutputStream rawPcm;
    private final Instant startedAt = Instant.now();

    private final ByteArrayOutputStream segment = new ByteArrayOutputStream(64 * 1024);
    private long totalBytes;
    private int segmentCount;
    private long lastCutMillis = System.currentTimeMillis();
    private boolean stopped;

    public CaptureSession(String sessionId, String device, String language, PcmFormat format, Path dir, OutputStream rawPcm) {
        this.sessionId = sessionId;
        this.device = device;
        this.language = language;
        this.format = format;
        this.dir = dir;
        this.rawPcm = rawPcm;
    }

    public void append(byte[] pcm) throws IOException {
        if (stopped) return;
        segment.write(pcm);
        rawPcm.write(pcm);
        totalBytes += pcm.length;
    }

    /** Returns the pending segment and starts a new one. Empty array if nothing was captured. */
    public byte[] cutSegment() {
        byte[] out = segment.toByteArray();
        segment.reset();
        lastCutMillis = System.currentTimeMillis();
        if (out.length > 0) segmentCount++;
        return out;
    }

    public void markStopped() throws IOException {
        stopped = true;
        rawPcm.flush();
        rawPcm.close();
    }

    public String sessionId() { return sessionId; }
    public String device() { return device; }
    public String language() { return language; }
    public PcmFormat format() { return format; }
    public Path dir() { return dir; }
    public Instant startedAt() { return startedAt; }
    public long totalBytes() { return totalBytes; }
    public int segmentCount() { return segmentCount; }
    public int pendingSegmentBytes() { return segment.size(); }
    public long millisSinceLastCut() { return System.currentTimeMillis() - lastCutMillis; }
    public boolean stopped() { return stopped; }
    public long totalDurationMillis() { return format.durationMillis(totalBytes); }
}
