package ee.evitec.tahti.fnol.agent;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Structured result of one claim-advisor round (steps 2-4 of the intake flow). */
@JsonClassDescription("Claim advisor's assessment after the latest transcript segment")
public record AdvisorDecision(
        @JsonPropertyDescription("VALMIS_KORVAUSRATKAISUUN when identity, loss date, damaged object and cause are "
                + "established and matched against the policy so a claim decision can be made (positive or negative); "
                + "LISAKYSYMYKSET when essential information is missing or two candidate paths remain") Status status,
        @JsonPropertyDescription("Validated hetu used for policy lookup, or null") String hetu,
        @JsonPropertyDescription("Loss date yyyy-MM-dd, or null") String lossDate,
        @JsonPropertyDescription("Caller name if known") String callerName,
        @JsonPropertyDescription("What happened, in Finnish, one or two sentences") String lossDescription,
        @JsonPropertyDescription("Policy elements the loss was matched to; empty when lookup was not possible")
        List<PolicyMatch> matchedPolicy,
        @JsonPropertyDescription("Filled only when status is VALMIS_KORVAUSRATKAISUUN") Resolution korvausratkaisu,
        @JsonPropertyDescription("Filled only when status is LISAKYSYMYKSET: short Finnish questions to ask the "
                + "caller, each targeting one fact that is missing or separates two candidate interpretations")
        List<String> lisakysymykset,
        @JsonPropertyDescription("Why this status; mention which tool results were decisive; Finnish, 1-3 sentences")
        String reasoning,
        @JsonPropertyDescription("Running Finnish summary of the whole call so far for the claim file") String summary) {

    public enum Status { VALMIS_KORVAUSRATKAISUUN, LISAKYSYMYKSET }

    public enum Outcome { KORVATTAVA, EI_KORVATTAVA, OSITTAIN_KORVATTAVA }

    @JsonClassDescription("A path through the policy structure the loss maps to")
    public record PolicyMatch(
            @JsonPropertyDescription("Insurable object name, e.g. 'VARASTOTIE 1, VANTAA, Irtaimisto'") String insurable,
            @JsonPropertyDescription("Coverage (Turva) name, e.g. 'Irtaimiston Tähtiturva'") String coverage,
            @JsonPropertyDescription("Risk (Riski) name the cause of loss maps to, e.g. 'Rikkoutuminen'") String risk,
            @JsonPropertyDescription("Claim type (Korvauslaji), e.g. 'Esinevahinko'") String claimType,
            @JsonPropertyDescription("Insurance number from the coverage") String insuranceNumber,
            @JsonPropertyDescription("Deductible in euros as text, e.g. '200.0'") String deductible,
            @JsonPropertyDescription("Term codes that apply, e.g. 'KO300, YL100'") String terms) {}

    @JsonClassDescription("Claim decision proposal")
    public record Resolution(
            @JsonPropertyDescription("KORVATTAVA, EI_KORVATTAVA or OSITTAIN_KORVATTAVA") Outcome outcome,
            @JsonPropertyDescription("Justification in Finnish referencing coverage, risk, terms and deductible")
            String perustelu,
            @JsonPropertyDescription("Next steps for the claim handler, Finnish") String seuraavatToimet) {}

    public boolean isReady() {
        return status == Status.VALMIS_KORVAUSRATKAISUUN;
    }

    public List<String> questions() {
        return lisakysymykset == null ? List.of() : lisakysymykset;
    }
}
