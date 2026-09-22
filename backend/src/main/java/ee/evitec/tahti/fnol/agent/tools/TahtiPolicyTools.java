package ee.evitec.tahti.fnol.agent.tools;

import ee.evitec.tahti.fnol.policy.TahtiPolicyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class TahtiPolicyTools {

    private static final Logger log = LoggerFactory.getLogger(TahtiPolicyTools.class);

    private final TahtiPolicyClient client;

    public TahtiPolicyTools(TahtiPolicyClient client) {
        this.client = client;
    }

    @Tool(description = "Get all valid insurable objects (e.g. Huoneisto, Irtaimisto, Ajoneuvo) where the "
            + "policyholder is identified by hetu (Finnish personal identity code) at the given target date "
            + "(yyyy-MM-dd). Start policy discovery here. Each element has OID, Type, TypeName and Attributes.")
    public String getInsurablesByPolicyholderHetu(
            @ToolParam(description = "Finnish personal identity code, e.g. 090798-921E") String hetu,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getInsurablesByPolicyholderHetu", hetu, targetdate,
                () -> client.insurablesByPolicyholderHetu(hetu, targetdate));
    }

    @Tool(description = "Get all valid coverages (Turva) belonging to an insurable object by the insurable OID "
            + "at the given target date (yyyy-MM-dd). Includes insurance number, deductible and sum insured.")
    public String getCoveragesByInsurableOid(
            @ToolParam(description = "OID of an insurable returned by getInsurablesByPolicyholderHetu") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getCoveragesByInsurableOid", oid, targetdate, () -> client.coveragesByInsurable(oid, targetdate));
    }

    @Tool(description = "Get all valid risks (Riski, e.g. Rikkoutuminen, Putkivuoto, Tulipalo, Rikos) belonging to a "
            + "coverage by the coverage OID at the given target date (yyyy-MM-dd). The risk list defines which "
            + "causes of loss the coverage responds to.")
    public String getRisksByCoverageOid(
            @ToolParam(description = "OID of a coverage returned by getCoveragesByInsurableOid") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getRisksByCoverageOid", oid, targetdate, () -> client.risksByCoverage(oid, targetdate));
    }

    @Tool(description = "Get all valid claim types (Korvauslaji / ecoverages, e.g. Esinevahinko) belonging to a "
            + "coverage by the coverage OID at the given target date (yyyy-MM-dd). Each claim type references its "
            + "RiskOID and carries the deductible that applies to that risk.")
    public String getEcoveragesByCoverageOid(
            @ToolParam(description = "OID of a coverage returned by getCoveragesByInsurableOid") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getEcoveragesByCoverageOid", oid, targetdate, () -> client.ecoveragesByCoverage(oid, targetdate));
    }

    @Tool(description = "Get all valid constraint terms/conditions (manually added special terms, exclusions or "
            + "restrictions) for a parent entity by its OID at the given target date (yyyy-MM-dd). Parent is "
            + "normally an insurable OID. An empty list means no special constraints.")
    public String getConstraintTermsByParentOid(
            @ToolParam(description = "OID of an insurable (or coverage)") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getConstraintTermsByParentOid", oid, targetdate,
                () -> client.constraintTermsByParent(oid, targetdate));
    }

    @Tool(description = "Get all valid standard/general terms (vakioehdot, e.g. 'KO300 Kodin vakuutukset') that "
            + "apply to an insurable entity by its OID at the given target date (yyyy-MM-dd). Returns term codes "
            + "and names; the TargetOid tells whether a term binds the insurable or one of its coverages.")
    public String getGeneralTermsByParentOid(
            @ToolParam(description = "OID of an insurable") String oid,
            @ToolParam(description = "Loss date, yyyy-MM-dd") String targetdate) {
        return call("getGeneralTermsByParentOid", oid, targetdate, () -> client.generalTermsByParent(oid, targetdate));
    }

    private interface Call {
        String run();
    }

    private String call(String tool, String a, String date, Call body) {
        long started = System.nanoTime();
        try {
            String result = body.run();
            log.info("[tool] {}({}, {}) -> {} chars in {} ms", tool, a, date, result.length(),
                    (System.nanoTime() - started) / 1_000_000);
            log.debug("[tool] {} result: {}", tool, result);
            return result;
        } catch (RuntimeException e) {
            log.warn("[tool] {}({}, {}) failed: {}", tool, a, date, e.toString());
            return "{\"error\":\"" + e.getClass().getSimpleName() + ": " + e.getMessage().replace('"', '\'') + "\"}";
        }
    }
}
