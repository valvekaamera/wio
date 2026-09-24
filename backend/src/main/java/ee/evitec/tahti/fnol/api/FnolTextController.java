package ee.evitec.tahti.fnol.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ee.evitec.tahti.fnol.agent.AdvisorDecision;
import ee.evitec.tahti.fnol.agent.ClaimAdvisor;
import ee.evitec.tahti.fnol.agent.FnolCase;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Text-only entry to the same claim-advisor loop the Wio drives through audio. Lets a
 * transcript be replayed without a microphone (testing, or a chat/telephony adapter that
 * already has text):
 * <pre>
 * POST   /api/fnol/{caseId}/segments   {"text": "..."}   -> one advisor round, returns the decision
 * POST   /api/fnol/{caseId}/hangup                       -> logs the FNOL summary and ends the case
 * DELETE /api/fnol/{caseId}                              -> forget the case without a summary
 * </pre>
 * Hang-up removes the case, so posting to the same caseId afterwards starts a new call
 * with an empty conversation.
 */
@RestController
@RequestMapping("/api/fnol")
@Validated
public class FnolTextController {

    public record SegmentRequest(@NotBlank String text) {}

    public record SegmentResponse(String caseId, int segment, int round, ClaimAdvisor.Verdict verdict,
                                  FnolCase.Status caseStatus, String statusNote, AdvisorDecision decision,
                                  String sayToClient) {}

    public record HangupResponse(String caseId, FnolCase.Status status, String statusNote, int segments,
                                 int rounds, List<String> questionsAsked) {}

    private static final Logger log = LoggerFactory.getLogger(FnolTextController.class);

    private final ClaimAdvisor advisor;
    private final Map<String, FnolCase> cases = new ConcurrentHashMap<>();

    public FnolTextController(ClaimAdvisor advisor) {
        this.advisor = advisor;
    }

    @PostMapping("/{caseId}/segments")
    public ResponseEntity<?> segment(@PathVariable String caseId, @RequestBody SegmentRequest body) {
        if (!advisor.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "claim advisor is disabled"));
        }
        FnolCase c = cases.computeIfAbsent(caseId, id -> new FnolCase(id, "text-api"));
        synchronized (c) {
            if (c.disposed()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "case ended, retry to start a new one"));
            }
            int segmentNo = c.transcripts().size() + 1;
            try {
                ClaimAdvisor.RoundResult r = advisor.advance(c, segmentNo, body.text());
                String say = r.verdict().endCall() ? advisor.closingPhrase() : String.join(" ", r.decision().questions());
                return ResponseEntity.ok(new SegmentResponse(caseId, segmentNo, c.rounds().size(), r.verdict(),
                        c.status(), c.statusNote(), r.decision(), say));
            } catch (RuntimeException e) {
                log.error("[{}] advisor round failed: {}", caseId, e.toString());
                c.recordError(e.toString());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of("error", "advisor round failed", "detail", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
    }

    @PostMapping("/{caseId}/hangup")
    public ResponseEntity<?> hangup(@PathVariable String caseId) {
        FnolCase c = cases.remove(caseId);
        if (c == null) return ResponseEntity.notFound().build();
        synchronized (c) {
            c.markHungUp();
            advisor.logSummary(c);
            var response = new HangupResponse(caseId, c.status(), c.statusNote(), c.transcripts().size(),
                    c.rounds().size(), c.allQuestionsAsked());
            c.dispose();
            return ResponseEntity.ok(response);
        }
    }

    @DeleteMapping("/{caseId}")
    public ResponseEntity<Void> forget(@PathVariable String caseId) {
        FnolCase c = cases.remove(caseId);
        if (c == null) return ResponseEntity.notFound().build();
        synchronized (c) {
            c.dispose();
        }
        return ResponseEntity.noContent().build();
    }
}
