package ee.evitec.tahti.fnol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Claim-advisor agent (LLM + policy tools) settings.
 *
 * @param enabled                   run the agent after each transcribed segment
 * @param closingPhrase             what the advisor says when a claim decision can be made
 * @param reasoningEffort           {@code reasoning_effort} for the tool-calling round
 *                                  (gpt-5 family: minimal|low|medium|high); blank = model default
 * @param extractionReasoningEffort {@code reasoning_effort} for the hetu/date extraction call; blank = default
 * @param maxQuestionsPerRound      cap on "Lisäkysymykset" per round so the caller is not interrogated
 * @param maxTranscriptChars        safety cap on transcript text sent to the model per round
 */
@ConfigurationProperties(prefix = "app.advisor")
public record AdvisorProperties(boolean enabled, String closingPhrase, String reasoningEffort,
                                String extractionReasoningEffort, int maxQuestionsPerRound,
                                int maxTranscriptChars) {

    public AdvisorProperties {
        if (closingPhrase == null || closingPhrase.isBlank()) closingPhrase = "Kiitos, otamme teihin pian yhteyttä.";
        if (maxQuestionsPerRound <= 0) maxQuestionsPerRound = 4;
        if (maxTranscriptChars <= 0) maxTranscriptChars = 12_000;
    }

    public boolean hasReasoningEffort() {
        return reasoningEffort != null && !reasoningEffort.isBlank();
    }

    public boolean hasExtractionReasoningEffort() {
        return extractionReasoningEffort != null && !extractionReasoningEffort.isBlank();
    }
}
