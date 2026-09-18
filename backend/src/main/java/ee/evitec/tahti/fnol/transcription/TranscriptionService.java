package ee.evitec.tahti.fnol.transcription;

import java.util.Optional;

import ee.evitec.tahti.fnol.config.TranscriptionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.azure.openai.AzureOpenAiAudioTranscriptionModel;
import org.springframework.ai.azure.openai.AzureOpenAiAudioTranscriptionOptions;
import org.springframework.ai.azure.openai.AzureOpenAiAudioTranscriptionOptions.TranscriptResponseFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

/**
 * Sends a WAV segment to the Whisper-class deployment in Azure AI Foundry and returns the text.
 * Whisper is batch-only, so callers chunk the stream and call this per segment.
 * If no transcription model is configured (e.g. {@code spring.ai.model.audio.transcription=none})
 * the service degrades to storing audio only.
 */
@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private final ObjectProvider<AzureOpenAiAudioTranscriptionModel> modelProvider;
    private final TranscriptionProperties properties;

    public TranscriptionService(ObjectProvider<AzureOpenAiAudioTranscriptionModel> modelProvider,
                                TranscriptionProperties properties) {
        this.modelProvider = modelProvider;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return modelProvider.getIfAvailable() != null;
    }

    /**
     * @param wav       complete WAV file bytes (header + PCM)
     * @param fileName  name passed to the API; the extension drives content-type detection
     * @param language  ISO-639-1 code, falls back to the configured default
     */
    public Optional<String> transcribe(byte[] wav, String fileName, String language) {
        var model = modelProvider.getIfAvailable();
        if (model == null) {
            log.warn("Transcription model not configured; skipping {}", fileName);
            return Optional.empty();
        }
        String lang = language == null || language.isBlank() ? properties.language() : language;

        var optionsBuilder = AzureOpenAiAudioTranscriptionOptions.builder()
                .language(lang)
                .temperature(0f)
                .responseFormat(TranscriptResponseFormat.TEXT);
        if (properties.prompt() != null && !properties.prompt().isBlank()) {
            optionsBuilder.prompt(properties.prompt());
        }

        var resource = new ByteArrayResource(wav) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        long started = System.nanoTime();
        AudioTranscriptionResponse response = model.call(new AudioTranscriptionPrompt(resource, optionsBuilder.build()));
        String text = response.getResult() == null ? null : response.getResult().getOutput();
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (text == null || text.isBlank()) {
            log.info("Transcription of {} returned no text ({} ms)", fileName, millis);
            return Optional.empty();
        }
        log.info("Transcription of {} done in {} ms", fileName, millis);
        return Optional.of(text.strip());
    }
}
