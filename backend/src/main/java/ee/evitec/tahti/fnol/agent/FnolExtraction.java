package ee.evitec.tahti.fnol.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Step 1 output: identity and loss-date facts pulled out of the transcript by the LLM.
 * Everything is "as heard" — validation happens in Java afterwards; a loss date is only
 * accepted when {@code lossDateEvidence} is actually found in the transcript.
 */
@JsonClassDescription("Facts extracted from a Finnish FNOL (first notice of loss) phone transcript")
public record FnolExtraction(
        @JsonPropertyDescription("Finnish personal identity code exactly as spoken/transcribed, "
                + "digits and check character only (e.g. 090798-921E); null if not mentioned") String hetu,
        @JsonPropertyDescription("Date of loss in yyyy-MM-dd, ONLY if the caller states it explicitly or with a "
                + "relative expression (tänään, eilen, viime perjantaina); resolve relative expressions against "
                + "'today'. null if the caller did not say when it happened - never default to today") String lossDate,
        @JsonPropertyDescription("The exact words from the transcript that state the loss date, copied verbatim "
                + "(e.g. '13.09.2026', 'eilen illalla'); null when lossDate is null") String lossDateEvidence,
        @JsonPropertyDescription("Caller's name if stated, else null") String callerName,
        @JsonPropertyDescription("One-sentence Finnish description of what was damaged/lost and how") String lossDescription,
        @JsonPropertyDescription("Other facts useful for a claim: object make/model, purchase year/place, "
                + "price, place of loss, who confirmed the damage; Finnish, short") String otherFacts) {
}
