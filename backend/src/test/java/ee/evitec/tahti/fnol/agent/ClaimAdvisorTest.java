package ee.evitec.tahti.fnol.agent;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.evitec.tahti.fnol.config.AdvisorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Case-status rules of the advisor, driven by a scripted chat model (no Azure, no REST). */
class ClaimAdvisorTest {

    private static final String HETU = "090798-921E";
    private static final String DATED = "Olen Ville, hetuni on 090798-921E. Koirani jäi auton alle 13.09.2026 ja kuoli.";

    /** Answers each call with the next scripted JSON: extraction, decision, extraction, decision... */
    static final class ScriptedModel implements ChatModel {
        final Deque<String> replies = new ArrayDeque<>();
        int calls;

        ScriptedModel reply(String json) {
            replies.add(json);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(replies.removeFirst()))));
        }
    }

    private static ClaimAdvisor advisor(ChatModel model) {
        var factory = new StaticListableBeanFactory(Map.of("model", model));
        ToolCallbackProvider noTools = () -> new ToolCallback[0];
        return new ClaimAdvisor(factory.getBeanProvider(ChatModel.class), noTools,
                new AdvisorProperties(true, null, null, null, 4, 12_000));
    }

    private static String extraction(String date, String evidence) {
        return """
                {"hetu":"%s","lossDate":%s,"lossDateEvidence":%s,"callerName":"Ville",
                 "lossDescription":"Koira kuoli","otherFacts":null}"""
                .formatted(HETU, date == null ? "null" : "\"" + date + "\"", evidence == null ? "null" : "\"" + evidence + "\"");
    }

    private static String decision(String outcome, String evidenceList, String nextSteps) {
        return """
                {"status":"PUHELU_VALMIS","callerName":"Ville","lossDescription":"Koira kuoli",
                 "matchedPolicy":[{"insurable":"Koira","insurableOid":"INS","coverage":"Eläinturva","coverageOid":"COV",
                   "risk":"Kuolema","riskOid":"RISK","claimType":null,"claimTypeOid":null,"insuranceNumber":"991-1",
                   "sumInsured":"999","basisForSumInsured":null,"deductible":"1","deductibleType":null,"terms":"EL100"}],
                 "korvausratkaisu":{"outcome":"%s","perustelu":"perustelu","seuraavatToimet":"%s"},
                 "lisaselvitykset":%s,"lisakysymykset":[],"reasoning":"r","summary":"s"}"""
                .formatted(outcome, nextSteps, evidenceList);
    }

    @Test
    void evidenceStillToBeSentKeepsCaseOpenAlthoughCallEnds() {
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("KORVATTAVA", "[\"Eläinlääkärin todistus kuolemasta\"]", "Avaa vahinkoilmoitus."));
        var c = new FnolCase("t", "test");
        var r = advisor(model).advance(c, 1, DATED);

        assertThat(r.verdict()).isEqualTo(ClaimAdvisor.Verdict.ODOTTAA_LISASELVITYKSIA);
        assertThat(r.verdict().endCall()).isTrue();
        c.markHungUp();
        assertThat(c.status()).isEqualTo(FnolCase.Status.KESKEN);
    }

    @Test
    void nextStepsWaitingForProofAlsoKeepCaseOpen() {
        String next = "Pyydä asiakkaalta eläinlääkärin tai muun luotettavan selvityksen kuolemasta. "
                + "Korvauskäsittely voidaan jatkaa toimitettujen selvitysten perusteella.";
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("KORVATTAVA", "[]", next));
        var c = new FnolCase("t", "test");
        var r = advisor(model).advance(c, 1, DATED);

        assertThat(r.caseStatus()).isEqualTo(FnolCase.Status.KESKEN);
        assertThat(r.verdict()).isEqualTo(ClaimAdvisor.Verdict.ODOTTAA_LISASELVITYKSIA);
    }

    @Test
    void compensableWithNothingPendingIsReady() {
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("KORVATTAVA", "[]", "Maksa korvaus omavastuulla vähennettynä."));
        var c = new FnolCase("t", "test");
        var r = advisor(model).advance(c, 1, DATED);
        c.markHungUp();

        assertThat(r.caseStatus()).isEqualTo(FnolCase.Status.VALMIS_KORVAUSRATKAISUUN);
        assertThat(c.status()).isEqualTo(FnolCase.Status.VALMIS_KORVAUSRATKAISUUN);
    }

    @Test
    void notCoveredGetsItsOwnStatusEverywhere() {
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("EI_KORVATTAVA", "[]", "Ilmoita asiakkaalle kirjallisesti."));
        var advisor = advisor(model);
        var c = new FnolCase("t", "test");
        var r = advisor.advance(c, 1, DATED);
        c.markHungUp();

        assertThat(r.verdict()).isEqualTo(ClaimAdvisor.Verdict.EI_KORVATTAVA);
        assertThat(c.status()).isEqualTo(FnolCase.Status.EI_KORVATTAVA);
        assertThat(advisor.summaryText(c)).contains("Tila: EI KORVATTAVA").doesNotContain("VALMIS KORVAUSRATKAISUUN");
    }

    @Test
    void lossDateIsNeverDefaultedToToday() {
        String today = LocalDate.now().toString();
        var model = new ScriptedModel().reply(extraction(today, null))
                .reply(decision("KORVATTAVA", "[]", "Maksa korvaus."));
        var c = new FnolCase("t", "test");
        var r = advisor(model).advance(c, 1, "Olen Ville, hetuni on 090798-921E. Koirani jäi auton alle.");

        assertThat(c.hasLossDate()).isFalse();
        assertThat(r.verdict()).isEqualTo(ClaimAdvisor.Verdict.LISAKYSYMYKSET);
        assertThat(r.decision().questions()).anyMatch(q -> q.contains("päivänä"));
    }

    @Test
    void relativeDateWithEvidenceIsAccepted() {
        String yesterday = LocalDate.now().minusDays(1).toString();
        var c = new FnolCase("t", "test");
        c.addTranscript(1, "Koirani jäi eilen illalla auton alle.");
        c.applyExtraction(new ObjectMapper().convertValue(Map.of("hetu", HETU, "lossDate", yesterday,
                "lossDateEvidence", "eilen illalla"), FnolExtraction.class));

        assertThat(c.lossDate()).hasToString(yesterday);
    }

    @Test
    void summaryQuotesPolicyValuesFromToolResults() throws Exception {
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("KORVATTAVA", "[]", "Maksa korvaus."));
        var advisor = advisor(model);
        var c = new FnolCase("t", "test");
        var json = new ObjectMapper();
        c.policyElements().put("COV", json.readTree("""
                {"OID":"COV","Attributes":{"InsuranceNumber":"991-1870594-001","SumInsured":"4390.51175049418",
                 "BasisForSumInsured":"10||Täysarvo","AmountDeductible":"200.0","DeductibleType":"20||Kiinteä"}}"""));
        c.policyElements().put("ECOV", json.readTree("""
                {"OID":"ECOV","Attributes":{"RiskOID":"RISK","SumInsured":"2200.0","AmountDeductible":"150.0"}}"""));
        advisor.advance(c, 1, DATED);

        assertThat(advisor.summaryText(c))
                .contains("Vakuutusnumero 991-1870594-001")
                .contains("Vakuutusmäärä 2200.00 €")          // claim type beats coverage
                .contains("Vakuutusmäärän peruste Täysarvo")
                .contains("Omavastuu 150.00 €")
                .contains("Omavastuutyyppi Kiinteä");
    }

    @Test
    void endedCaseForgetsConversation() {
        var model = new ScriptedModel().reply(extraction("2026-09-13", "13.09.2026"))
                .reply(decision("KORVATTAVA", "[]", "Maksa korvaus."));
        var c = new FnolCase("t", "test");
        advisor(model).advance(c, 1, DATED);
        assertThat(c.history()).hasSize(2);

        c.dispose();
        assertThat(c.history()).isEmpty();
        assertThat(c.policyElements()).isEmpty();
    }
}
