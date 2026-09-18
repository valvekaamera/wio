package ee.evitec.tahti.fnol.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import ee.evitec.tahti.fnol.config.AudioProperties;
import org.springframework.stereotype.Component;

/**
 * File layout: {@code <storage-dir>/<yyyyMMdd-HHmmss>-<sessionId>/segment-NNN.wav|session.wav}.
 * Files are kept so captured audio can be inspected and replayed against the model.
 */
@Component
public class AudioStorage {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AudioProperties properties;

    public AudioStorage(AudioProperties properties) {
        this.properties = properties;
    }

    public Path createSessionDir(String sessionId) throws IOException {
        String safeId = sessionId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path dir = properties.storageDir().resolve(LocalDateTime.now().format(STAMP) + "-" + safeId);
        Files.createDirectories(dir);
        return dir;
    }

    public OutputStream openRawPcm(Path sessionDir) throws IOException {
        return Files.newOutputStream(rawPcmPath(sessionDir),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public Path rawPcmPath(Path sessionDir) {
        return sessionDir.resolve("session.pcm");
    }

    public Path writeSegment(Path sessionDir, int segmentNo, PcmFormat format, byte[] pcm) throws IOException {
        Path target = sessionDir.resolve(String.format("segment-%03d.wav", segmentNo));
        WavWriter.write(target, format, pcm);
        return target;
    }

    public Path finalizeSession(Path sessionDir, PcmFormat format) throws IOException {
        Path raw = rawPcmPath(sessionDir);
        Path target = sessionDir.resolve("session.wav");
        if (Files.exists(raw)) {
            WavWriter.wrapPcmFile(raw, target, format);
            Files.delete(raw);
        } else {
            WavWriter.write(target, format, new byte[0]);
        }
        return target;
    }
}
