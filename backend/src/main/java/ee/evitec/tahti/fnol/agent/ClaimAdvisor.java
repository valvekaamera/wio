package ee.evitec.tahti.fnol.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ee.evitec.tahti.fnol.config.AdvisorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The claim-advisor agent loop, one {@link #advance} call per transcribed segment:
 * <ol>
 *   <li>extract hetu + loss date from the transcript (LLM), validate hetu in Java;</li>
 *   <li>let the model discover the policy with the tool-set (insurables → coverages → risks /
 *       claim types → terms) — only once hetu and date are known;</li>
 *   <li>if the loss matches the policy unambiguously → {@code VALMIS_KORVAUSRATKAISUUN}, the
 *       handler says the closing phrase and hangs up (button B);</li>
 *   <li>otherwise → {@code LISAKYSYMYKSET}, logged with the transcript so far; the next
 *       segment (button A) feeds the answers back into the same conversation;</li>
 *   <li>{@link #logSummary} prints the call summary with status KESKEN / VALMIS.</li>
 * </ol>
 * Messages are passed as {@link Message} objects (not {@code .user()/.system()} strings) so
 * transcript text and JSON schema braces never reach the prompt template engine.
 */
@Service
public class ClaimAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ClaimAdvisor.class);
    private static final DateTimeFormatter FI_DATE = DateTimeFormatter.ofPattern("d.M.yyyy");

    private static final String SYSTEM_PROMPT = """
            You are "Korvausneuvoja", the claim advisor of a Finnish insurer handling FNOL (first notice of \
            loss, vahinkoilmoitus) phone calls. A human claim handler is on the phone with the client and reads \
            your output; the client's speech reaches you as Finnish speech-to-text segments, one per round. \
            Speech-to-text errors are common, especially in numbers, dates and names.

            Your job each round:
            1. Understand the accumulated transcript: who is calling (hetu), when the loss happened, which \
            object was damaged or lost, what caused it, and whether several objects, causes or locations are \
            involved.
            2. When the round input marks the hetu VALID and a loss date is known, discover the client's policy \
            with the tools in this order: getInsurablesByPolicyholderHetu -> getCoveragesByInsurableOid (for \
            every insurable that could contain the damaged object) -> getRisksByCoverageOid and \
            getEcoveragesByCoverageOid (for the candidate coverages) -> getConstraintTermsByParentOid and \
            getGeneralTermsByParentOid (for the matched insurable). Use getEntityTypesByIds or \
            searchEntityTypesByName only for type codes that arrive without a TypeName.
            3. Match the loss against the policy structure: the damaged object must belong to an insurable, \
            the cause must map to a risk under a coverage that is valid (Status Voimassa) on the loss date, \
            and a claim type (Korvauslaji) must exist for that risk. Consider every candidate path, e.g. a \
            household appliance may be Irtaimisto or a fixed fixture of Huoneisto; a broken machine may be \
            Rikkoutuminen, or Putkivuoto if water escaped and damaged the building.
            4. Decide the status:
               - VALMIS_KORVAUSRATKAISUUN when identity, loss date, object, cause and the policy match are \
            unambiguous and the tool data supports a claim decision, positive or negative. Fill \
            korvausratkaisu with outcome, justification (coverage, risk, terms, deductible) and next steps.
               - LISAKYSYMYKSET otherwise. Ask only what is essential: a fact that is missing, or the single \
            fact that separates two candidate paths. Short, plain spoken Finnish, one fact per question, \
            never repeat a question the client already answered.

            Rules:
            - Everything the insurer considers proprietary - which coverages, risks, claim types and terms \
            exist, deductibles, sums insured, validity - comes ONLY from tool results. Never assume product \
            structure. An empty tool result is a fact to reason about (e.g. no valid coverage on the loss date).
            - Never invent facts about the loss. If the transcript is unclear, ask.
            - Do not call policy tools while the round input marks the hetu INVALID or missing, or the loss \
            date missing: ask the client to repeat the hetu digit by digit, or to state the date.
            - Keep the summary cumulative so it stands alone as the claim-file note of the whole call so far.
            - All free-text output in Finnish. Output exactly one JSON object as specified in the round \
            input, no prose before or after it.
            """;

    private static final String EXTRACTION_PROMPT = """
            You extract facts from a Finnish FNOL (vahinkoilmoitus) phone transcript produced by speech-to-text. \
            Report the hetu (henkilötunnus) exactly as heard, keeping only digits and the separator/check \
            character (e.g. "09078-921E" stays "09078-921E" even if it looks wrong). Convert dates to \
            yyyy-MM-dd, resolving relative expressions ("viime perjantaina", "eilen") against today's date. \
            Never guess values that are not in the transcript; use null. Output exactly one JSON object.
            """;

    private final ChatClient chat;                 // null when no chat model / disabled
    private final ToolCallbackProvider tools;
    private final AdvisorProperties props;
    private final Clock clock;
    private final BeanOutputConverter<FnolExtraction> extractionConverter = new BeanOutputConverter<>(FnolExtraction.class);
    private final BeanOutputConverter<AdvisorDecision> decisionConverter = new BeanOutputConverter<>(AdvisorDecision.class);

    public ClaimAdvisor(ObjectProvider<ChatModel> chatModel, ToolCallbackProvider tools, AdvisorProperties props) {
        // ObjectProvider on the model, not on ChatClient.Builder: the builder bean is always
        // defined and would fail on lookup when spring.ai.model.chat=none.
        ChatModel model = chatModel.getIfAvailable();
        this.chat = props.enabled() && model != null ? ChatClient.builder(model).build() : null;
        this.tools = tools;
        this.props = props;
        this.clock = Clock.systemDefaultZone();
        if (this.chat == null) {
            log.warn("Claim advisor disabled ({}).", props.enabled() ? "no chat model configured" : "app.advisor.enabled=false");
        } else {
            log.info("Claim advisor enabled with {} tools", tools.getToolCallbacks().length);
        }
    }

    public boolean isEnabled() {
        return chat != null;
    }

    public String closingPhrase() {
        return props.closingPhrase();
    }

    // ---------------------------------------------------------------- steps 1-4

    /**
     * Runs one advisor round on a newly transcribed segment. Blocking (seconds to tens of
     * seconds); the caller runs it on the session's advisor executor.
     */
    public AdvisorDecision advance(FnolCase c, int segmentNo, String transcript) {
        if (chat == null) throw new IllegalStateException("claim advisor is disabled");
        c.addTranscript(segmentNo, transcript);
        long started = System.nanoTime();
        LocalDate today = LocalDate.now(clock);

        // Step 1 — identity + loss date, re-extracted until both are established.
        if (!c.hasValidHetu() || !c.hasLossDate()) {
            FnolExtraction extraction = extract(c, today);
            c.applyExtraction(extraction);
            log.info("[{}] extraction: hetu={} ({}), lossDate={}, caller={}, loss={}",
                    c.sessionId(), c.hetuHeard() == null ? "-" : c.hetuHeard(),
                    c.hasValidHetu() ? "VALID" : Hetu.problem(c.hetuHeard()),
                    c.hasLossDate() ? c.lossDate() : "-",
                    extraction == null ? "-" : extraction.callerName(),
                    extraction == null ? "-" : extraction.lossDescription());
        }

        // Steps 2-4 — policy discovery + decision, with tools only once the keys are known.
        boolean toolsAllowed = c.hasValidHetu() && c.hasLossDate();
        String roundText = roundMessage(c, segmentNo, transcript, today, toolsAllowed);
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.addAll(c.history());
        messages.add(new UserMessage(roundText + "\n\n" + decisionConverter.getFormat()));

        var request = chat.prompt().messages(messages).options(options(props.reasoningEffort()));
        if (toolsAllowed) request = request.toolCallbacks(tools);
        String content = request.call().content();
        AdvisorDecision decision = decisionConverter.convert(content);
        if (decision == null || decision.status() == null) {
            throw new IllegalStateException("advisor returned no status: " + abbreviate(content, 300));
        }
        decision = capQuestions(decision);

        c.history().add(new UserMessage(roundText));
        c.history().add(new AssistantMessage(content));
        long millis = (System.nanoTime() - started) / 1_000_000;
        c.recordRound(segmentNo, decision, millis);
        logDecision(c, decision, millis);
        return decision;
    }

    private FnolExtraction extract(FnolCase c, LocalDate today) {
        String user = "Tänään on " + today + " (" + today.format(FI_DATE) + ").\n\nTranskriptio:\n\"\"\"\n"
                + truncate(c.fullTranscript()) + "\"\"\"\n\n" + extractionConverter.getFormat();
        String content = chat.prompt()
                .messages(new SystemMessage(EXTRACTION_PROMPT), new UserMessage(user))
                .options(options(props.extractionReasoningEffort()))
                .call()
                .content();
        return extractionConverter.convert(content);
    }

    private String roundMessage(FnolCase c, int segmentNo, String transcript, LocalDate today, boolean toolsAllowed) {
        var sb = new StringBuilder();
        sb.append("Kierros ").append(c.rounds().size() + 1).append(". Tänään on ").append(today).append(".\n\n");
        sb.append("Uusi transkriptio (segmentti ").append(segmentNo).append("):\n\"\"\"\n")
          .append(transcript.strip()).append("\n\"\"\"\n\n");
        if (c.transcripts().size() > 1) {
            sb.append("Koko transkriptio tähän asti:\n\"\"\"\n").append(truncate(c.fullTranscript())).append("\"\"\"\n\n");
        }
        sb.append("Esitiedot (validoitu ohjelmallisesti):\n");
        if (c.hasValidHetu()) {
            sb.append("- hetu: ").append(c.hetu()).append(" VALID\n");
        } else if (c.hetuHeard() != null && !c.hetuHeard().isEmpty()) {
            sb.append("- hetu: INVALID - ").append(Hetu.problem(c.hetuHeard())).append('\n');
        } else {
            sb.append("- hetu: puuttuu\n");
        }
        sb.append("- vahinkopäivä: ").append(c.hasLossDate() ? c.lossDate().toString() : "puuttuu").append('\n');
        if (c.extraction() != null) {
            if (c.extraction().callerName() != null) sb.append("- soittaja: ").append(c.extraction().callerName()).append('\n');
            if (c.extraction().lossDescription() != null) sb.append("- vahinko: ").append(c.extraction().lossDescription()).append('\n');
            if (c.extraction().otherFacts() != null) sb.append("- muut faktat: ").append(c.extraction().otherFacts()).append('\n');
        }
        sb.append("- vakuutustyökalut: ").append(toolsAllowed
                ? "käytettävissä - tee vakuutuksen selvitys nyt"
                : "EI käytettävissä tällä kierroksella (hetu tai vahinkopäivä puuttuu) - pyydä puuttuva tieto").append('\n');
        List<String> asked = c.allQuestionsAsked();
        if (!asked.isEmpty()) {
            sb.append("- aiemmin kysytyt lisäkysymykset: ").append(String.join(" | ", asked)).append('\n');
        }
        sb.append("- enintään ").append(props.maxQuestionsPerRound()).append(" lisäkysymystä tällä kierroksella\n");
        return sb.toString();
    }

    private AzureOpenAiChatOptions options(String reasoningEffort) {
        var b = AzureOpenAiChatOptions.builder()
                .responseFormat(AzureOpenAiResponseFormat.builder().type(AzureOpenAiResponseFormat.Type.JSON_OBJECT).build());
        if (reasoningEffort != null && !reasoningEffort.isBlank()) b.reasoningEffort(reasoningEffort.trim());
        return b.build();
    }

    private AdvisorDecision capQuestions(AdvisorDecision d) {
        List<String> q = d.questions();
        if (q.size() <= props.maxQuestionsPerRound()) return d;
        return new AdvisorDecision(d.status(), d.hetu(), d.lossDate(), d.callerName(), d.lossDescription(),
                d.matchedPolicy(), d.korvausratkaisu(), q.subList(0, props.maxQuestionsPerRound()),
                d.reasoning(), d.summary());
    }

    // ---------------------------------------------------------------- logging (steps 3-5)

    private void logDecision(FnolCase c, AdvisorDecision d, long millis) {
        String id = c.sessionId();
        var sb = new StringBuilder();
        sb.append('\n').append("[").append(id).append("] ---- KIERROS ").append(c.rounds().size())
          .append(" (").append(millis).append(" ms) -> ").append(d.status()).append('\n');
        if (d.reasoning() != null) sb.append("Perustelu: ").append(d.reasoning()).append('\n');
        appendPolicy(sb, d);
        if (d.isReady()) {
            if (d.korvausratkaisu() != null) {
                sb.append("KORVAUSRATKAISU: ").append(d.korvausratkaisu().outcome()).append(" - ")
                  .append(d.korvausratkaisu().perustelu()).append('\n');
                if (d.korvausratkaisu().seuraavatToimet() != null) {
                    sb.append("Seuraavat toimet: ").append(d.korvausratkaisu().seuraavatToimet()).append('\n');
                }
            }
            sb.append("=> Sano asiakkaalle: \"").append(props.closingPhrase())
              .append("\" ja lopeta puhelu (B).\n");
        } else {
            sb.append("Transkriptio tähän mennessä:\n");
            for (FnolCase.Segment s : c.transcripts()) {
                sb.append("  [").append(s.segmentNo()).append("] ").append(s.text().strip()).append('\n');
            }
            sb.append("LISÄKYSYMYKSET - kysy asiakkaalta, sitten paina A:\n");
            int n = 1;
            for (String q : d.questions()) sb.append("  ").append(n++).append(". ").append(q).append('\n');
            if (d.questions().isEmpty()) sb.append("  (malli ei antanut kysymyksiä)\n");
        }
        log.info(sb.toString());
    }

    /** Step 5: final call summary, status VALMIS only if the decision was reached before hang-up. */
    public void logSummary(FnolCase c) {
        String id = c.sessionId();
        AdvisorDecision d = c.lastDecision();
        String status = c.status() == FnolCase.Status.VALMIS_KORVAUSRATKAISUUN ? "VALMIS KORVAUSRATKAISUUN" : "KESKEN";
        Duration dur = Duration.between(c.startedAt(), clock.instant());
        var sb = new StringBuilder();
        sb.append('\n').append("[").append(id).append("] ================= FNOL-YHTEENVETO =================\n");
        sb.append("Tila: ").append(status).append('\n');
        sb.append("Puhelu: alkoi ").append(c.startedAt().atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0))
          .append(", kesto ").append(String.format("%02d:%02d", dur.toMinutes(), dur.toSecondsPart()))
          .append(", segmenttejä ").append(c.transcripts().size())
          .append(", neuvojakierroksia ").append(c.rounds().size())
          .append(", laite ").append(c.device()).append('\n');
        String caller = Optional.ofNullable(d).map(AdvisorDecision::callerName)
                .or(() -> Optional.ofNullable(c.extraction()).map(FnolExtraction::callerName)).orElse("-");
        sb.append("Soittaja: ").append(caller)
          .append("   Hetu: ").append(c.hasValidHetu() ? c.hetu() : (c.hetuHeard() == null ? "-" : c.hetuHeard() + " (ei validi)"))
          .append("   Vahinkopäivä: ").append(c.hasLossDate() ? c.lossDate() : "-").append('\n');
        String loss = Optional.ofNullable(d).map(AdvisorDecision::lossDescription)
                .or(() -> Optional.ofNullable(c.extraction()).map(FnolExtraction::lossDescription)).orElse("-");
        sb.append("Vahinko: ").append(loss).append('\n');
        if (d != null) appendPolicy(sb, d);
        if (d != null && d.korvausratkaisu() != null) {
            sb.append("Korvausratkaisu: ").append(d.korvausratkaisu().outcome()).append(" - ")
              .append(d.korvausratkaisu().perustelu()).append('\n');
            if (d.korvausratkaisu().seuraavatToimet() != null) {
                sb.append("Seuraavat toimet: ").append(d.korvausratkaisu().seuraavatToimet()).append('\n');
            }
        } else {
            sb.append("Korvausratkaisu: ei tehty\n");
        }
        List<String> asked = c.allQuestionsAsked();
        if (!asked.isEmpty()) {
            sb.append("Lisäkysymykset esitetty (").append(asked.size()).append("):\n");
            int n = 1;
            for (String q : asked) sb.append("  ").append(n++).append(". ").append(q).append('\n');
        }
        if (d != null && d.summary() != null) sb.append("Yhteenveto: ").append(d.summary()).append('\n');
        if (c.lastError() != null) sb.append("Viimeinen virhe: ").append(c.lastError()).append('\n');
        sb.append("Transkriptio:\n");
        for (FnolCase.Segment s : c.transcripts()) {
            sb.append("  [").append(s.segmentNo()).append("] ").append(s.text().strip()).append('\n');
        }
        if (c.transcripts().isEmpty()) sb.append("  (ei transkriptiota)\n");
        sb.append("[").append(id).append("] ====================================================");
        log.info(sb.toString());
    }

    private static void appendPolicy(StringBuilder sb, AdvisorDecision d) {
        if (d.matchedPolicy() == null || d.matchedPolicy().isEmpty()) return;
        sb.append("Vakuutus:\n");
        for (AdvisorDecision.PolicyMatch m : d.matchedPolicy()) {
            sb.append("  - ").append(nvl(m.insurable())).append(" / ").append(nvl(m.coverage()))
              .append(" / ").append(nvl(m.risk())).append(" / ").append(nvl(m.claimType()));
            if (m.insuranceNumber() != null) sb.append("  (").append(m.insuranceNumber()).append(')');
            if (m.deductible() != null) sb.append("  omavastuu ").append(m.deductible());
            if (m.terms() != null) sb.append("  ehdot ").append(m.terms());
            sb.append('\n');
        }
    }

    private static String nvl(String s) {
        return s == null ? "-" : s;
    }

    private String truncate(String text) {
        int max = props.maxTranscriptChars();
        if (text.length() <= max) return text;
        return "...(alku lyhennetty)...\n" + text.substring(text.length() - max);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
