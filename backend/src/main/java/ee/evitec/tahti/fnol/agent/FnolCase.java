package ee.evitec.tahti.fnol.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * Everything the advisor knows about one FNOL call. One instance per capture session;
 * mutated only on that session's advisor executor, so no synchronisation is needed.
 */
public final class FnolCase {

    public enum Status { KESKEN, VALMIS_KORVAUSRATKAISUUN }

    public record Segment(int segmentNo, String text) {}

    public record Round(int roundNo, int segmentNo, AdvisorDecision decision, long millis) {}

    private final String sessionId;
    private final String device;
    private final Instant startedAt = Instant.now();
    private final List<Segment> transcripts = new ArrayList<>();
    private final List<Message> history = new ArrayList<>();
    private final List<Round> rounds = new ArrayList<>();

    private String hetu;            // validated
    private String hetuHeard;       // last value as transcribed, valid or not
    private LocalDate lossDate;
    private FnolExtraction extraction;
    private AdvisorDecision lastDecision;
    private Status status = Status.KESKEN;
    private boolean hungUp;
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
    public String hetu() { return hetu; }
    public String hetuHeard() { return hetuHeard; }
    public LocalDate lossDate() { return lossDate; }
    public FnolExtraction extraction() { return extraction; }
    public AdvisorDecision lastDecision() { return lastDecision; }
    public Status status() { return status; }
    public boolean hungUp() { return hungUp; }
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

    public void applyExtraction(FnolExtraction e) {
        this.extraction = e;
        if (e == null) return;
        if (e.hetu() != null && !e.hetu().isBlank()) {
            hetuHeard = Hetu.normalize(e.hetu());
            Hetu.validate(e.hetu()).ifPresent(v -> hetu = v);
        }
        if (e.lossDate() != null && !e.lossDate().isBlank()) {
            try {
                lossDate = LocalDate.parse(e.lossDate().strip());
            } catch (RuntimeException ignored) {
                // model returned a non-ISO date; leave unset so the advisor asks for it
            }
        }
    }

    public void recordRound(int segmentNo, AdvisorDecision decision, long millis) {
        rounds.add(new Round(rounds.size() + 1, segmentNo, decision, millis));
        lastDecision = decision;
        lastError = null;
        // The model may correct hetu/date after a follow-up answer.
        if (decision.hetu() != null) Hetu.validate(decision.hetu()).ifPresent(v -> hetu = v);
        if (decision.lossDate() != null) {
            try { lossDate = LocalDate.parse(decision.lossDate().strip()); } catch (RuntimeException ignored) { }
        }
        // A decision reached after the advisor already hung up does not change the call outcome.
        if (!hungUp) {
            status = decision.isReady() ? Status.VALMIS_KORVAUSRATKAISUUN : Status.KESKEN;
        }
    }

    public void recordError(String message) {
        lastError = message;
    }

    public void markHungUp() {
        hungUp = true;
    }

    public List<String> allQuestionsAsked() {
        var out = new ArrayList<String>();
        for (Round r : rounds) out.addAll(r.decision().questions());
        return out;
    }
}
