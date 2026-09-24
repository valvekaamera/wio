package ee.evitec.tahti.fnol.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured result of one claim-advisor round (steps 2-4 of the intake flow). The model
 * only says whether the call can end and what it concluded; the case status
 * (KESKEN / VALMIS KORVAUSRATKAISUUN / EI KORVATTAVA) is derived from this in Java.
 */
@JsonClassDescription("Claim advisor's assessment after the latest transcript segment")
public record AdvisorDecision(
        @JsonPropertyDescription("PUHELU_VALMIS when everything that can be obtained by phone is collected: identity, "
                + "loss date, damaged object and cause are established and matched against the policy data, so the call "
                + "can be ended. LISAKYSYMYKSET when essential information is still missing or two candidate paths "
                + "remain and the caller must be asked") Status status,
        @JsonPropertyDescription("Caller name if known") String callerName,
        @JsonPropertyDescription("What happened, in Finnish, one or two sentences") String lossDescription,
        @JsonPropertyDescription("Policy elements the loss was matched to; empty when lookup was not possible")
        List<PolicyMatch> matchedPolicy,
        @JsonPropertyDescription("Filled when status is PUHELU_VALMIS") Resolution korvausratkaisu,
        @JsonPropertyDescription("Documents or evidence the caller must deliver AFTER the call before the claim can "
                + "be decided (e.g. eläinlääkärin todistus, ostokuitti, korjaajan lausunto, rikosilmoitus). Every "
                + "request for proof belongs here, not only in seuraavatToimet. Empty only when the decision can be "
                + "made from the phone call and the policy data alone") List<String> lisaselvitykset,
        @JsonPropertyDescription("Filled only when status is LISAKYSYMYKSET: short Finnish questions to ask the "
                + "caller now, each targeting one fact that is missing or separates two candidate interpretations")
        List<String> lisakysymykset,
        @JsonPropertyDescription("Why this status; mention which tool results were decisive; Finnish, 1-3 sentences")
        String reasoning,
        @JsonPropertyDescription("Finnish summary of this call so far for the claim file, stated as facts of this "
                + "call only") String summary) {

    public enum Status { PUHELU_VALMIS, LISAKYSYMYKSET }

    public enum Outcome { KORVATTAVA, OSITTAIN_KORVATTAVA, EI_KORVATTAVA }

    @JsonClassDescription("A path through the policy structure the loss maps to")
    public record PolicyMatch(
            @JsonPropertyDescription("Insurable object name, e.g. 'VARASTOTIE 1, VANTAA, Irtaimisto'") String insurable,
            @JsonPropertyDescription("OID of that insurable, copied from the tool result") String insurableOid,
            @JsonPropertyDescription("Coverage (Turva) name, e.g. 'Irtaimiston Tähtiturva'") String coverage,
            @JsonPropertyDescription("OID of that coverage, copied from the tool result") String coverageOid,
            @JsonPropertyDescription("Risk (Riski) name the cause of loss maps to, e.g. 'Rikkoutuminen'") String risk,
            @JsonPropertyDescription("OID of that risk, copied from the tool result") String riskOid,
            @JsonPropertyDescription("Claim type (Korvauslaji), e.g. 'Esinevahinko'") String claimType,
            @JsonPropertyDescription("OID of the claim type (ecoverage) whose RiskOID is the matched risk")
            String claimTypeOid,
            @JsonPropertyDescription("InsuranceNumber from the tool result") String insuranceNumber,
            @JsonPropertyDescription("SumInsured (vakuutusmäärä) as in the tool result") String sumInsured,
            @JsonPropertyDescription("BasisForSumInsured (vakuutusmäärän peruste) as in the tool result")
            String basisForSumInsured,
            @JsonPropertyDescription("AmountDeductible (omavastuu) as in the tool result") String deductible,
            @JsonPropertyDescription("DeductibleType (omavastuutyyppi) as in the tool result") String deductibleType,
            @JsonPropertyDescription("Term codes that apply, e.g. 'KO300, YL100'") String terms) {}

    @JsonClassDescription("Claim decision proposal")
    public record Resolution(
            @JsonPropertyDescription("KORVATTAVA, OSITTAIN_KORVATTAVA or EI_KORVATTAVA") Outcome outcome,
            @JsonPropertyDescription("Justification in Finnish referencing coverage, risk, terms and deductible")
            String perustelu,
            @JsonPropertyDescription("Next steps for the claim handler, Finnish") String seuraavatToimet) {}

    public boolean callCanEnd() {
        return status == Status.PUHELU_VALMIS;
    }

    public List<String> questions() {
        return lisakysymykset == null ? List.of() : lisakysymykset;
    }

    public List<String> pendingEvidence() {
        return lisaselvitykset == null ? List.of() : lisaselvitykset;
    }

    public List<PolicyMatch> policy() {
        return matchedPolicy == null ? List.of() : matchedPolicy;
    }

    public Outcome outcome() {
        return korvausratkaisu == null ? null : korvausratkaisu.outcome();
    }

    /** Same decision with the call forced to continue and the given questions (guards in Java). */
    public AdvisorDecision withQuestions(List<String> questions, String extraReasoning) {
        String why = reasoning == null ? extraReasoning : reasoning + " " + extraReasoning;
        return new AdvisorDecision(Status.LISAKYSYMYKSET, callerName, lossDescription, matchedPolicy, null,
                lisaselvitykset, questions, why, summary);
    }
}
