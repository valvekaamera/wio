package ee.evitec.tahti.fnol.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audio ingest settings. See docs/audio-ws-protocol.md.
 *
 * @param storageDir           directory where session and segment WAV files are written
 * @param defaultSampleRate    sample rate assumed when the client does not announce one
 * @param autoSegmentSeconds   0 = transcribe only on "transcribe" command; N>0 = also cut every N s
 * @param maxSegmentSeconds    hard cap on a single segment so memory cannot grow unbounded
 * @param minTranscribeMillis  segments shorter than this are stored but not sent to the model
 */
@ConfigurationProperties(prefix = "app.audio")
public record AudioProperties(
        Path storageDir,
        int defaultSampleRate,
        int autoSegmentSeconds,
        int maxSegmentSeconds,
        int minTranscribeMillis) {

    public AudioProperties {
        if (storageDir == null) storageDir = Path.of("recordings");
        if (defaultSampleRate <= 0) defaultSampleRate = 16_000;
        if (maxSegmentSeconds <= 0) maxSegmentSeconds = 600;
        if (minTranscribeMillis < 0) minTranscribeMillis = 0;
    }
}
