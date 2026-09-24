package ee.evitec.tahti.fnol.agent.tools;

import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import ee.evitec.tahti.fnol.agent.FnolCase;
import ee.evitec.tahti.fnol.policy.TahtiPolicyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Policy-discovery tool-set exposed to the claim-advisor LLM. Same names, arguments and
 * descriptions as the tahti MCP server so the agent prompt is portable between the
 * in-process tools used here and a remote MCP deployment.
 *
 * Discovery order the model is expected to follow:
 * insurables (by hetu) -> coverages (per insurable) -> risks + claim types (per coverage)
 * -> constraint/general terms (per insurable or coverage).
 *
 * When called by the advisor the {@link ToolContext} carries the call's {@link FnolCase}:
 * the validated hetu and loss date of that case replace whatever the model passed, and
 * every returned element is recorded in the case so the summary can quote exact values.
 */
@Component
public class TahtiPolicyTools {

    public static final String CASE_KEY = "fnolCase";

    private static final Logger log = LoggerFactory.getLogger(TahtiPolicyTools.class);

    private final TahtiPolicyClient client;

    public TahtiPolicyTools(TahtiPolicyClient client) {
        this.client = client;
    }

    @Tool(description = "Get all valid insurable objects (e.g. Huoneisto, Irtaimisto, Ajoneuvo, Koira) where the "
            + "policyholder is identified by hetu (Finnish personal identity code) at the given target date "
            + "(yyyy-MM-dd). Start policy discovery here. Each element has OID, Type, TypeName and Attributes.")
    public String getInsurablesByPolicyholderHetu(
            @ToolParam(description = "Finnish personal identity code, e.g. 090798-921E") String hetu,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String h = c != null && c.hasValidHetu() ? enforce("hetu", hetu, c.hetu()) : hetu;
        String d = date(c, targetdate);
        return call(c, "getInsurablesByPolicyholderHetu", h, d, () -> client.insurablesByPolicyholderHetu(h, d));
    }

    @Tool(description = "Get all valid coverages (Turva) belonging to an insurable object by the insurable OID "
            + "at the given target date (yyyy-MM-dd). Includes insurance number, deductible, deductible type, "
            + "sum insured and its basis.")
    public String getCoveragesByInsurableOid(
            @ToolParam(description = "OID of an insurable returned by getInsurablesByPolicyholderHetu") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String d = date(c, targetdate);
        return call(c, "getCoveragesByInsurableOid", oid, d, () -> client.coveragesByInsurable(oid, d));
    }

    @Tool(description = "Get all valid risks (Riski, e.g. Rikkoutuminen, Putkivuoto, Tulipalo, Rikos) belonging to a "
            + "coverage by the coverage OID at the given target date (yyyy-MM-dd). The risk list defines which "
            + "causes of loss the coverage responds to.")
    public String getRisksByCoverageOid(
            @ToolParam(description = "OID of a coverage returned by getCoveragesByInsurableOid") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String d = date(c, targetdate);
        return call(c, "getRisksByCoverageOid", oid, d, () -> client.risksByCoverage(oid, d));
    }

    @Tool(description = "Get all valid claim types (Korvauslaji / ecoverages, e.g. Esinevahinko) belonging to a "
            + "coverage by the coverage OID at the given target date (yyyy-MM-dd). Each claim type references its "
            + "RiskOID and carries the sum insured and deductible that apply to that risk.")
    public String getEcoveragesByCoverageOid(
            @ToolParam(description = "OID of a coverage returned by getCoveragesByInsurableOid") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String d = date(c, targetdate);
        return call(c, "getEcoveragesByCoverageOid", oid, d, () -> client.ecoveragesByCoverage(oid, d));
    }

    @Tool(description = "Get all valid constraint terms/conditions (manually added special terms, exclusions or "
            + "restrictions) for a parent entity by its OID at the given target date (yyyy-MM-dd). Parent is "
            + "normally an insurable OID. An empty list means no special constraints.")
    public String getConstraintTermsByParentOid(
            @ToolParam(description = "OID of an insurable (or coverage)") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String d = date(c, targetdate);
        return call(c, "getConstraintTermsByParentOid", oid, d, () -> client.constraintTermsByParent(oid, d));
    }

    @Tool(description = "Get all valid standard/general terms (vakioehdot, e.g. 'KO300 Kodin vakuutukset') that "
            + "apply to an insurable entity by its OID at the given target date (yyyy-MM-dd). Returns term codes "
            + "and names; the TargetOid tells whether a term binds the insurable or one of its coverages.")
    public String getGeneralTermsByParentOid(
            @ToolParam(description = "OID of an insurable") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate,
            ToolContext context) {
        FnolCase c = fnolCase(context);
        String d = date(c, targetdate);
        return call(c, "getGeneralTermsByParentOid", oid, d, () -> client.generalTermsByParent(oid, d));
    }

    // ---------------------------------------------------------------- internals

    private static FnolCase fnolCase(ToolContext context) {
        if (context == null || context.getContext() == null) return null;
        return context.getContext().get(CASE_KEY) instanceof FnolCase c ? c : null;
    }

    private static String date(FnolCase c, String requested) {
        return c != null && c.hasLossDate() ? enforce("targetdate", requested, c.lossDate().toString()) : requested;
    }

    private static String enforce(String what, String requested, String validated) {
        if (requested != null && !requested.strip().equals(validated)) {
            log.warn("[tool] model passed {}={} - using the validated value {}", what, requested, validated);
        }
        return validated;
    }

    private String call(FnolCase c, String tool, String a, String date, Supplier<JsonNode> body) {
        long started = System.nanoTime();
        String id = c == null ? "-" : c.sessionId();
        try {
            JsonNode result = body.get();
            if (c != null) {
                for (JsonNode el : result.path("elements")) {
                    String oid = el.path("OID").asText(null);
                    if (oid != null) c.policyElements().put(oid, el);
                }
            }
            String json = client.toJson(result);
            log.info("[{}] [tool] {}({}, {}) -> {} items, {} chars in {} ms", id, tool, a, date,
                    result.path("count").asInt(), json.length(), (System.nanoTime() - started) / 1_000_000);
            log.debug("[{}] [tool] {} result: {}", id, tool, json);
            return json;
        } catch (RuntimeException e) {
            log.warn("[{}] [tool] {}({}, {}) failed: {}", id, tool, a, date, e.toString());
            String msg = e.getMessage() == null ? "" : e.getMessage().replace('"', '\'');
            return "{\"error\":\"" + e.getClass().getSimpleName() + ": " + msg + "\"}";
        }
    }
}
