package ee.evitec.tahti.fnol.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import ee.evitec.tahti.fnol.agent.AdvisorDecision.Outcome;
import ee.evitec.tahti.fnol.agent.AdvisorDecision.PolicyMatch;
import ee.evitec.tahti.fnol.agent.tools.TahtiPolicyTools;
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
 *   <li>extract hetu + loss date from the whole transcript (LLM) every round; hetu is
 *       validated in Java, a loss date is accepted only with evidence from the transcript;</li>
 *   <li>let the model discover the policy with the tool-set (insurables → coverages → risks /
 *       claim types → terms) — only once hetu and date are known, and pinned to them;</li>
 *   <li>the model says whether the call can end; the case status is derived here:
 *       {@code VALMIS_KORVAUSRATKAISUUN} (compensable, nothing pending), {@code EI_KORVATTAVA}
 *       (not compensable, nothing pending) or {@code KESKEN} (questions open, or evidence the
 *       caller must send after the call);</li>
 *   <li>open questions are logged with the transcript so far; the next segment continues the
 *       same conversation;</li>
 *   <li>{@link #logSummary} prints the call summary; the case is then disposed.</li>
 * </ol>
 * Each call has its own {@link FnolCase}; there is no chat memory shared between calls.
 * Messages are passed as {@link Message} objects (not {@code .user()/.system()} strings) so
 * transcript text and JSON schema braces never reach the prompt template engine.
 */
@Service
public class ClaimAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ClaimAdvisor.class);
    private static final DateTimeFormatter FI_DATE = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final Pattern DATE_QUESTION = Pattern.compile("(?i)(päivä|milloin|ajankohta)");
    private static final Pattern HETU_QUESTION = Pattern.compile("(?i)(henkilötunnus|hetu)");
    /** Safety net: next steps that wait for documents from the caller mean the case is not decided yet. */
    private static final Pattern EVIDENCE_PENDING = Pattern.compile(
            "(?i)((pyydä|pyydetään|toimita|toimitta|lähettä|liittä).{0,80}"
            + "(todistu|selvity|selvitys|kuitti|kuita|lausun|ilmoitu|dokument|liite|valokuv|kuvat))"
            + "|((selvitysten|selvityksen|todistuksen|kuitin|lausunnon|liitteiden) perusteella)");

    private static final String SYSTEM_PROMPT = """
            You are "Korvausneuvoja", the claim advisor of a Finnish insurer handling FNOL (first notice of \
            loss, vahinkoilmoitus) phone calls. A human claim handler is on the phone with the client and reads \
            your output; the client's speech reaches you as Finnish speech-to-text segments, one per round. \
            Speech-to-text errors are common, especially in numbers, dates and names. Every call is a separate \
            case: you know nothing about other calls or other clients, and you refer to "earlier" information \
            only when it appears in the messages of this conversation.

            Your job each round:
            1. Understand the accumulated transcript: who is calling (hetu), when the loss happened, which \
            object was damaged or lost, what caused it, and whether several objects, causes or locations are \
            involved.
            2. When the round input marks the hetu VALID and a loss date is known, discover the client's policy \
            with the tools in this order: getInsurablesByPolicyholderHetu -> getCoveragesByInsurableOid (for \
            every insurable that could contain the damaged object) -> getRisksByCoverageOid and \
            getEcoveragesByCoverageOid (for the candidate coverages) -> getConstraintTermsByParentOid and \
            getGeneralTermsByParentOid (for the matched insurable). Always pass the hetu and loss date given \
            in the round input. Use getEntityTypesByIds or searchEntityTypesByName only for type codes that \
            arrive without a TypeName.
            3. Match the loss against the policy structure: the damaged object must belong to an insurable, \
            the cause must map to a risk under a coverage that is valid (Status Voimassa) on the loss date, \
            and a claim type (Korvauslaji) must exist for that risk. Consider every candidate path, e.g. a \
            household appliance may be Irtaimisto or a fixed fixture of Huoneisto; a broken machine may be \
            Rikkoutuminen, or Putkivuoto if water escaped and damaged the building. In matchedPolicy copy the \
            OIDs and the values InsuranceNumber, SumInsured, BasisForSumInsured, AmountDeductible and \
            DeductibleType exactly as the tools returned them (claim type first, then risk, then coverage).
            4. Decide the status:
               - PUHELU_VALMIS when everything that can be obtained by phone is collected and matched against \
            the tool data. Fill korvausratkaisu: KORVATTAVA / OSITTAIN_KORVATTAVA / EI_KORVATTAVA with \
            justification (coverage, risk, terms, deductible) and next steps. If the decision still depends \
            on proof the client must send after the call (vet or doctor certificate, receipt, repair \
            estimate, police report, photos...), list each item in lisaselvitykset - the claim is then NOT \
            ready for decision even though the call can end. If no valid coverage responds to the loss, the \
            outcome is EI_KORVATTAVA and lisaselvitykset stays empty.
               - LISAKYSYMYKSET otherwise. Ask only what is essential and can be answered on the phone now: a \
            fact that is missing, or the single fact that separates two candidate paths. Short, plain spoken \
            Finnish, one fact per question, never repeat a question the client already answered.

            Rules:
            - Everything the insurer considers proprietary - which coverages, risks, claim types and terms \
            exist, deductibles, sums insured, validity - comes ONLY from tool results. Never assume product \
            structure. An empty tool result is a fact to reason about (e.g. no valid coverage on the loss date).
            - Never invent facts about the loss. If the transcript is unclear, ask.
            - The loss date is never assumed: if the round input says it is missing, ask when the loss \
            happened. Do not call policy tools while the hetu is INVALID or missing, or the loss date missing.
            - Keep the summary cumulative for this call so it stands alone as the claim-file note.
            - All free-text output in Finnish. Output exactly one JSON object as specified in the round \
            input, no prose before or after it.
            """;

    private static final String EXTRACTION_PROMPT = """
            You extract facts from a Finnish FNOL (vahinkoilmoitus) phone transcript produced by speech-to-text. \
            The transcript may contain answers to follow-up questions; later statements correct earlier ones. \
            Report the hetu (henkilötunnus) exactly as heard, keeping only digits and the separator/check \
            character (e.g. "09078-921E" stays "09078-921E" even if it looks wrong). Report the loss date only \
            if the caller says when the loss happened - an explicit date or a relative expression ("tänään", \
            "eilen", "viime perjantaina") resolved against today's date - and copy the words that say it into \
            lossDateEvidence. Today's date is given only for resolving such expressions; it is never a default. \
            Never guess values that are not in the transcript; use null. Output exactly one JSON object.
            """;

    /** What the claim handler should do after a round; maps 1:1 to the "advisor" event status. */
    public enum Verdict {
        LISAKYSYMYKSET(false),
        VALMIS_KORVAUSRATKAISUUN(true),
        EI_KORVATTAVA(true),
        ODOTTAA_LISASELVITYKSIA(true);

        private final boolean endCall;

        Verdict(boolean endCall) {
            this.endCall = endCall;
        }

        public boolean endCall() {
            return endCall;
        }
    }

    public record RoundResult(AdvisorDecision decision, Verdict verdict, FnolCase.Status caseStatus, String note) {}

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
     * seconds); the caller runs it on the call's advisor executor.
     */
    public RoundResult advance(FnolCase c, int segmentNo, String transcript) {
        if (chat == null) throw new IllegalStateException("claim advisor is disabled");
        if (c.disposed()) throw new IllegalStateException("case " + c.sessionId() + " has ended");
        c.addTranscript(segmentNo, transcript);
        long started = System.nanoTime();
        LocalDate today = LocalDate.now(clock);
        log.info("[{}] advisor round {} starts: {} earlier messages, {} policy elements known in this call",
                c.sessionId(), c.rounds().size() + 1, c.history().size(), c.policyElements().size());

        // Step 1 — identity + loss date from the whole transcript, every round (answers may correct them).
        FnolExtraction extraction = extract(c, today);
        c.applyExtraction(extraction);
        log.info("[{}] extraction: hetu={} ({}), lossDate={} (evidence: {}), caller={}, loss={}",
                c.sessionId(), c.hetuHeard() == null ? "-" : c.hetuHeard(),
                c.hasValidHetu() ? "VALID" : Hetu.problem(c.hetuHeard()),
                c.hasLossDate() ? c.lossDate() : "-",
                c.lossDateEvidence() == null ? "-" : "'" + c.lossDateEvidence() + "'",
                extraction == null ? "-" : extraction.callerName(),
                extraction == null ? "-" : extraction.lossDescription());
        if (extraction != null && extraction.lossDate() != null && !c.hasLossDate()) {
            log.info("[{}] loss date {} rejected: evidence '{}' not found in the transcript",
                    c.sessionId(), extraction.lossDate(), extraction.lossDateEvidence());
        }

        // Steps 2-4 — policy discovery + decision, tools only once the keys are known.
        boolean toolsAllowed = c.hasValidHetu() && c.hasLossDate();
        String roundText = roundMessage(c, segmentNo, transcript, today, toolsAllowed);
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.addAll(c.history());
        messages.add(new UserMessage(roundText + "\n\n" + decisionConverter.getFormat()));

        var request = chat.prompt().messages(messages).options(options(props.reasoningEffort()));
        if (toolsAllowed) {
            request = request.toolCallbacks(tools).toolContext(Map.of(TahtiPolicyTools.CASE_KEY, c));
        }
        String content = request.call().content();
        AdvisorDecision decision = decisionConverter.convert(content);
        if (decision == null || decision.status() == null) {
            throw new IllegalStateException("advisor returned no status: " + abbreviate(content, 300));
        }
        decision = capQuestions(guardKeys(c, decision));

        c.history().add(new UserMessage(roundText));
        c.history().add(new AssistantMessage(content));
        RoundResult result = classify(decision);
        long millis = (System.nanoTime() - started) / 1_000_000;
        c.recordRound(segmentNo, decision, result.caseStatus(), result.note(), millis);
        logDecision(c, result, millis);
        return result;
    }

    /** Without a valid hetu and an evidenced loss date the call cannot end; make sure both are asked. */
    private AdvisorDecision guardKeys(FnolCase c, AdvisorDecision d) {
        if (c.hasValidHetu() && c.hasLossDate()) return d;
        var questions = new ArrayList<>(d.questions());
        var missing = new ArrayList<String>();
        if (!c.hasLossDate()) {
            missing.add("vahinkopäivä");
            if (questions.stream().noneMatch(q -> DATE_QUESTION.matcher(q).find())) {
                questions.add(0, "Minä päivänä vahinko tapahtui?");
            }
        }
        if (!c.hasValidHetu()) {
            missing.add("henkilötunnus");
            if (questions.stream().noneMatch(q -> HETU_QUESTION.matcher(q).find())) {
                questions.add(0, "Voisitteko kertoa henkilötunnuksenne numero kerrallaan?");
            }
        }
        if (d.callCanEnd() || questions.size() != d.questions().size()) {
            log.info("[{}] guard: {} missing - call continues with questions", c.sessionId(), String.join(", ", missing));
            return d.withQuestions(questions, "(Tarkistus: " + String.join(" ja ", missing) + " puuttuu.)");
        }
        return d;
    }

    /** Case status is decided here, not by the model, so it cannot contradict the decision. */
    private RoundResult classify(AdvisorDecision d) {
        if (!d.callCanEnd()) {
            return new RoundResult(d, Verdict.LISAKYSYMYKSET, FnolCase.Status.KESKEN, "lisäkysymyksiä avoinna");
        }
        if (!d.pendingEvidence().isEmpty()) {
            return new RoundResult(d, Verdict.ODOTTAA_LISASELVITYKSIA, FnolCase.Status.KESKEN,
                    "odottaa asiakkaan lisäselvityksiä: " + String.join("; ", d.pendingEvidence()));
        }
        Outcome outcome = d.outcome();
        if (outcome == null) {
            return new RoundResult(d, Verdict.ODOTTAA_LISASELVITYKSIA, FnolCase.Status.KESKEN, "korvausratkaisua ei annettu");
        }
        if (outcome != Outcome.EI_KORVATTAVA) {
            String next = d.korvausratkaisu().seuraavatToimet();
            if (next != null && EVIDENCE_PENDING.matcher(next).find()) {
                return new RoundResult(d, Verdict.ODOTTAA_LISASELVITYKSIA, FnolCase.Status.KESKEN,
                        "seuraavat toimet edellyttävät asiakkaan lisäselvityksiä");
            }
            return new RoundResult(d, Verdict.VALMIS_KORVAUSRATKAISUUN, FnolCase.Status.VALMIS_KORVAUSRATKAISUUN,
                    "korvausratkaisu voidaan tehdä");
        }
        return new RoundResult(d, Verdict.EI_KORVATTAVA, FnolCase.Status.EI_KORVATTAVA, "vahinko ei ole korvattava");
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
        int round = c.rounds().size() + 1;
        sb.append("Puhelu ").append(c.sessionId()).append(", kierros ").append(round)
          .append(". Tänään on ").append(today).append(".\n");
        if (round == 1) sb.append("Tämä on puhelun ensimmäinen kierros - aiempaa keskustelua tai tulkintaa ei ole.\n");
        sb.append('\n');
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
        if (c.hasLossDate()) {
            sb.append("- vahinkopäivä: ").append(c.lossDate()).append(" (asiakas: \"").append(c.lossDateEvidence()).append("\")\n");
        } else {
            sb.append("- vahinkopäivä: PUUTTUU - asiakas ei ole kertonut, milloin vahinko tapahtui; kysy se\n");
        }
        if (c.extraction() != null) {
            if (c.extraction().callerName() != null) sb.append("- soittaja: ").append(c.extraction().callerName()).append('\n');
            if (c.extraction().lossDescription() != null) sb.append("- vahinko: ").append(c.extraction().lossDescription()).append('\n');
            if (c.extraction().otherFacts() != null) sb.append("- muut faktat: ").append(c.extraction().otherFacts()).append('\n');
        }
        sb.append("- vakuutustyökalut: ").append(toolsAllowed
                ? "käytettävissä hetulla " + c.hetu() + " ja päivällä " + c.lossDate() + " - tee vakuutuksen selvitys nyt"
                : "EI käytettävissä tällä kierroksella (hetu tai vahinkopäivä puuttuu) - pyydä puuttuva tieto").append('\n');
        List<String> asked = c.allQuestionsAsked();
        if (!asked.isEmpty()) {
            sb.append("- aiemmin tässä puhelussa kysytyt lisäkysymykset: ").append(String.join(" | ", asked)).append('\n');
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
        return new AdvisorDecision(d.status(), d.callerName(), d.lossDescription(), d.matchedPolicy(),
                d.korvausratkaisu(), d.lisaselvitykset(), q.subList(0, props.maxQuestionsPerRound()),
                d.reasoning(), d.summary());
    }

    // ---------------------------------------------------------------- logging (steps 3-5)

    private void logDecision(FnolCase c, RoundResult r, long millis) {
        AdvisorDecision d = r.decision();
        var sb = new StringBuilder();
        sb.append('\n').append("[").append(c.sessionId()).append("] ---- KIERROS ").append(c.rounds().size())
          .append(" (").append(millis).append(" ms) -> ").append(r.verdict())
          .append("   tila: ").append(r.caseStatus().label()).append(" (").append(r.note()).append(")\n");
        if (d.reasoning() != null) sb.append("Perustelu: ").append(d.reasoning()).append('\n');
        appendPolicy(sb, c, d);
        appendResolution(sb, d);
        if (r.verdict().endCall()) {
            sb.append("=> Sano asiakkaalle: \"").append(props.closingPhrase()).append("\" ja lopeta puhelu (B).\n");
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

    /** Step 5: final call summary; the status is the one derived before hang-up. */
    public void logSummary(FnolCase c) {
        log.info(summaryText(c));
    }

    public String summaryText(FnolCase c) {
        String id = c.sessionId();
        AdvisorDecision d = c.lastDecision();
        Duration dur = Duration.between(c.startedAt(), clock.instant());
        var sb = new StringBuilder();
        sb.append('\n').append("[").append(id).append("] ================= FNOL-YHTEENVETO =================\n");
        sb.append("Tila: ").append(c.status().label()).append(" (").append(c.statusNote()).append(")\n");
        sb.append("Puhelu: alkoi ").append(c.startedAt().atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0))
          .append(", kesto ").append(String.format("%02d:%02d", dur.toMinutes(), dur.toSecondsPart()))
          .append(", segmenttejä ").append(c.transcripts().size())
          .append(", neuvojakierroksia ").append(c.rounds().size())
          .append(", laite ").append(c.device()).append('\n');
        String caller = Optional.ofNullable(d).map(AdvisorDecision::callerName)
                .or(() -> Optional.ofNullable(c.extraction()).map(FnolExtraction::callerName)).orElse("-");
        sb.append("Soittaja: ").append(caller)
          .append("   Hetu: ").append(c.hasValidHetu() ? c.hetu() : (c.hetuHeard() == null ? "-" : c.hetuHeard() + " (ei validi)"))
          .append("   Vahinkopäivä: ").append(c.hasLossDate() ? c.lossDate() : "- (ei kerrottu)").append('\n');
        String loss = Optional.ofNullable(d).map(AdvisorDecision::lossDescription)
                .or(() -> Optional.ofNullable(c.extraction()).map(FnolExtraction::lossDescription)).orElse("-");
        sb.append("Vahinko: ").append(loss).append('\n');
        if (d != null) appendPolicy(sb, c, d);
        if (d != null && d.korvausratkaisu() != null) {
            appendResolution(sb, d);
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
        return sb.toString();
    }

    private static void appendResolution(StringBuilder sb, AdvisorDecision d) {
        if (d.korvausratkaisu() != null && d.korvausratkaisu().outcome() != null) {
            sb.append("Korvausratkaisuehdotus: ").append(d.korvausratkaisu().outcome()).append(" - ")
              .append(d.korvausratkaisu().perustelu()).append('\n');
            if (d.korvausratkaisu().seuraavatToimet() != null) {
                sb.append("Seuraavat toimet: ").append(d.korvausratkaisu().seuraavatToimet()).append('\n');
            }
        }
        if (!d.pendingEvidence().isEmpty()) {
            sb.append("Asiakkaalta odotettavat lisäselvitykset:\n");
            for (String e : d.pendingEvidence()) sb.append("  - ").append(e).append('\n');
        }
    }

    /**
     * Policy path plus the attributes claim calculation needs. Values are read from the tool
     * results of this call (claim type, then risk, then coverage); the model's copy is only a
     * fallback, marked with '*'.
     */
    private static void appendPolicy(StringBuilder sb, FnolCase c, AdvisorDecision d) {
        if (d.policy().isEmpty()) return;
        sb.append("Vakuutus:\n");
        for (PolicyMatch m : d.policy()) {
            List<JsonNode> chain = chain(c, m);
            sb.append("  - ").append(nvl(m.insurable())).append(" / ").append(nvl(m.coverage()))
              .append(" / ").append(nvl(m.risk())).append(" / ").append(nvl(m.claimType())).append('\n');
            sb.append("      Vakuutusnumero ").append(value(chain, "InsuranceNumber", m.insuranceNumber(), false))
              .append(" | Vakuutusmäärä ").append(value(chain, "SumInsured", m.sumInsured(), true))
              .append(" | Vakuutusmäärän peruste ").append(value(chain, "BasisForSumInsured", m.basisForSumInsured(), false))
              .append('\n');
            sb.append("      Omavastuu ").append(value(chain, "AmountDeductible", m.deductible(), true))
              .append(" | Omavastuutyyppi ").append(value(chain, "DeductibleType", m.deductibleType(), false))
              .append(" | Ehdot ").append(nvl(m.terms())).append('\n');
        }
    }

    /** Claim type, risk and coverage elements of a match, most specific first. */
    private static List<JsonNode> chain(FnolCase c, PolicyMatch m) {
        Map<String, JsonNode> els = c.policyElements();
        var out = new ArrayList<JsonNode>();
        JsonNode claimType = m.claimTypeOid() == null ? null : els.get(m.claimTypeOid());
        if (claimType == null && m.riskOid() != null) {
            // The claim type of a risk is the ecoverage whose RiskOID points at it.
            claimType = els.values().stream()
                    .filter(e -> m.riskOid().equals(e.path("Attributes").path("RiskOID").asText(null)))
                    .findFirst().orElse(null);
        }
        if (claimType != null) out.add(claimType);
        if (m.riskOid() != null && els.get(m.riskOid()) != null) out.add(els.get(m.riskOid()));
        if (m.coverageOid() != null && els.get(m.coverageOid()) != null) out.add(els.get(m.coverageOid()));
        return out;
    }

    private static String value(List<JsonNode> chain, String attribute, String modelValue, boolean money) {
        for (JsonNode el : chain) {
            String v = el.path("Attributes").path(attribute).asText(null);
            if (v != null && !v.isBlank()) return money ? PolicyValues.amount(v) : PolicyValues.label(v);
        }
        if (modelValue == null || modelValue.isBlank()) return "-";
        return (money ? PolicyValues.amount(modelValue) : PolicyValues.label(modelValue)) + "*";
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
