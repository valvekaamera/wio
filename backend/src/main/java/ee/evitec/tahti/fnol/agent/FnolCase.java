package ee.evitec.tahti.fnol.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.chat.messages.Message;

/**
 * Everything the advisor knows about one FNOL call. One instance per call; nothing in it is
 * shared with other calls, and {@link #dispose()} drops the LLM conversation and cached
 * policy data when the call ends. Mutated only on that call's advisor executor.
 */
public final class FnolCase {

    public enum Status {
        KESKEN("KESKEN"),
        VALMIS_KORVAUSRATKAISUUN("VALMIS KORVAUSRATKAISUUN"),
        EI_KORVATTAVA("EI KORVATTAVA");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Segment(int segmentNo, String text) {}

    public record Round(int roundNo, int segmentNo, AdvisorDecision decision, Status status, long millis) {}

    private final String sessionId;
    private final String device;
    private final Instant startedAt = Instant.now();
    private final List<Segment> transcripts = new ArrayList<>();
    private final List<Message> history = new ArrayList<>();
    private final List<Round> rounds = new ArrayList<>();
    /** Policy elements returned by the tools during this call, by OID (compacted JSON). */
    private final Map<String, JsonNode> policyElements = new ConcurrentHashMap<>();

    private String hetu;            // validated
    private String hetuHeard;       // last value as transcribed, valid or not
    private LocalDate lossDate;     // accepted only with transcript evidence
    private String lossDateEvidence;
    private FnolExtraction extraction;
    private AdvisorDecision lastDecision;
    private Status status = Status.KESKEN;
    private String statusNote = "puhelu kesken";
    private boolean hungUp;
    private boolean disposed;
    private String lastError;

    public FnolCase(String sessionId, String device) {
        this.sessionId = sessionId;
        this.device = device;
    }

    public String sessionId() { return sessionId; }
    public String device() { return device; }
    public Instant startedAt() { return startedAt; }
    public List<Segment> transcripts() { return transcripts; }
    public List<Message> history() { return history; }
    public List<Round> rounds() { return rounds; }
    public Map<String, JsonNode> policyElements() { return policyElements; }
    public String hetu() { return hetu; }
    public String hetuHeard() { return hetuHeard; }
    public LocalDate lossDate() { return lossDate; }
    public String lossDateEvidence() { return lossDateEvidence; }
    public FnolExtraction extraction() { return extraction; }
    public AdvisorDecision lastDecision() { return lastDecision; }
    public Status status() { return status; }
    public String statusNote() { return statusNote; }
    public boolean hungUp() { return hungUp; }
    public boolean disposed() { return disposed; }
    public String lastError() { return lastError; }

    public boolean hasValidHetu() { return hetu != null; }
    public boolean hasLossDate() { return lossDate != null; }

    public void addTranscript(int segmentNo, String text) {
        transcripts.add(new Segment(segmentNo, text));
    }

    public String fullTranscript() {
        var sb = new StringBuilder();
        for (Segment s : transcripts) {
            sb.append('[').append(s.segmentNo()).append("] ").append(s.text().strip()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Merges a fresh extraction over the whole transcript. A valid hetu replaces the previous
     * one (the caller may have corrected it); a loss date is taken only when its evidence
     * phrase really occurs in the transcript, so a model default of "today" is rejected.
     */
    public void applyExtraction(FnolExtraction e) {
        this.extraction = e;
        if (e == null) return;
        if (e.hetu() != null && !e.hetu().isBlank()) {
            hetuHeard = Hetu.normalize(e.hetu());
            Hetu.validate(e.hetu()).ifPresent(v -> hetu = v);
        }
        if (e.lossDate() != null && !e.lossDate().isBlank() && evidenceInTranscript(e.lossDateEvidence())) {
            try {
                lossDate = LocalDate.parse(e.lossDate().strip());
                lossDateEvidence = e.lossDateEvidence().strip();
            } catch (RuntimeException ignored) {
                // non-ISO date from the model; keep the previous value so the advisor asks again
            }
        }
    }

    boolean evidenceInTranscript(String evidence) {
        if (evidence == null || evidence.isBlank()) return false;
        String haystack = fullTranscript().toLowerCase(Locale.ROOT);
        for (String token : evidence.toLowerCase(Locale.ROOT).split("\\s+")) {
            String t = token.replaceAll("^[\\p{Punct}\"'«»]+|[\\p{Punct}\"'«»]+$", "");
            if (t.length() >= 2 && !haystack.contains(t)) return false;
        }
        return true;
    }

    public void recordRound(int segmentNo, AdvisorDecision decision, Status derived, String note, long millis) {
        rounds.add(new Round(rounds.size() + 1, segmentNo, decision, derived, millis));
        lastDecision = decision;
        lastError = null;
        // A decision reached after the handler hung up does not change the call outcome.
        if (!hungUp) {
            status = derived;
            statusNote = note;
        }
    }

    public void recordError(String message) {
        lastError = message;
    }

    public void markHungUp() {
        if (hungUp) return;
        hungUp = true;
        if (lastDecision == null || !lastDecision.callCanEnd()) {
            status = Status.KESKEN;
            statusNote = "puhelu lopetettiin ennen kuin tiedot olivat valmiit";
        }
    }

    /** Ends the case: the LLM conversation and cached policy data are dropped. */
    public void dispose() {
        disposed = true;
        history.clear();
        policyElements.clear();
    }

    public List<String> allQuestionsAsked() {
        var out = new ArrayList<String>();
        for (Round r : rounds) out.addAll(r.decision().questions());
        return out;
    }
}
