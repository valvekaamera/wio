package ee.evitec.tahti.fnol.agent;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Step 1 output: identity and loss-date facts pulled out of the transcript by the LLM.
 * Everything is "as heard" — validation happens in Java afterwards.
 */
@JsonClassDescription("Facts extracted from a Finnish FNOL (first notice of loss) phone transcript")
public record FnolExtraction(
        @JsonPropertyDescription("Finnish personal identity code exactly as spoken/transcribed, "
                + "digits and check character only (e.g. 090798-921E); null if not mentioned") String hetu,
        @JsonPropertyDescription("Date of loss in yyyy-MM-dd; resolve relative expressions against 'today'; "
                + "null if not mentioned") String lossDate,
        @JsonPropertyDescription("Caller's name if stated, else null") String callerName,
        @JsonPropertyDescription("One-sentence Finnish description of what was damaged/lost and how") String lossDescription,
        @JsonPropertyDescription("Other facts useful for a claim: object make/model, purchase year/place, "
                + "price, who confirmed the damage; Finnish, short") String otherFacts) {
}
