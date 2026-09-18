package ee.evitec.tahti.fnol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param language ISO-639-1 language forced on the model (Whisper drifts if left to auto-detect)
 * @param prompt   vocabulary hint passed to Whisper to bias domain terms
 */
@ConfigurationProperties(prefix = "app.transcription")
public record TranscriptionProperties(String language, String prompt) {

    public TranscriptionProperties {
        if (language == null || language.isBlank()) language = "fi";
    }
}
