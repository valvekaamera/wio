package ee.evitec.tahti.fnol.policy;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a policyholder's valid policy structure from tahti-rest-app and compacts the
 * answers for an LLM context: the raw API returns every attribute twice (current +
 * "PreviousValue"), transient pricing intermediates and session bookkeeping OIDs. Only
 * business attributes are kept, and every type code is enriched with its Finnish name
 * from the ontology so the model does not have to look up "138.210" separately.
 */
@Component
public class TahtiPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(TahtiPolicyClient.class);
    private static final String DATA = "/tahti-data";

    /** Attribute names that are bookkeeping, not business facts. */
    private static final Pattern NOISE = Pattern.compile(
            "(?i).*(PreviousValue|PreviousSession|Session|SubSession|ActivityNumber|FolderOID|FirstProposal.*|"
            + "ProcessingState|Formula.*|Tariff.*|MultiplRiskCoef|.*Prem(ium)?(WithoutTaxes|1000)?|"
            + "BalSeasonalBasicPrem|BalancedSeasonalPremium|InsuranceTax|TaxablePremium|SumInsured1000|"
            + "SumOfAnalvalOfPropNoCov|Index|Certificate.*|OriginatedFrom|EntitledToBonus).*");
    /** Kept even though they match NOISE-ish patterns: cross-references the model navigates by. */
    private static final Set<String> KEEP = Set.of("ParentOID", "RiskOID", "InsObjOID", "CoverageOID", "PolicyOID",
            "Premium", "RiskPremium");

    private final RestClient client;
    private final InsuranceOntology ontology;
    private final ObjectMapper mapper;

    public TahtiPolicyClient(@Qualifier("tahtiRestClient") RestClient client, InsuranceOntology ontology,
                             ObjectMapper mapper) {
        this.client = client;
        this.ontology = ontology;
        this.mapper = mapper;
    }

    public JsonNode insurablesByPolicyholderHetu(String hetu, String targetDate) {
        return elements("/valid_insurables_policyholder/hetu/{hetu}/targetdate/{date}/option/both", hetu, targetDate);
    }

    public JsonNode coveragesByInsurable(String insurableOid, String targetDate) {
        return elements("/valid_coverages_insurable/{oid}/targetdate/{date}/option/both", insurableOid, targetDate);
    }

    public JsonNode risksByCoverage(String coverageOid, String targetDate) {
        return elements("/valid_risks_coverage/{oid}/targetdate/{date}/option/both", coverageOid, targetDate);
    }

    public JsonNode ecoveragesByCoverage(String coverageOid, String targetDate) {
        return elements("/valid_ecoverages_coverage/{oid}/targetdate/{date}/option/both", coverageOid, targetDate);
    }

    public JsonNode constraintTermsByParent(String parentOid, String targetDate) {
        return elements("/valid_terms_parent/{oid}/targetdate/{date}/option/both", parentOid, targetDate);
    }

    public JsonNode generalTermsByParent(String parentOid, String targetDate) {
        JsonNode root = get("/valid_gen_terms_parent/{oid}/targetdate/{date}", parentOid, targetDate);
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode t : root.path("terms")) {
            ObjectNode o = out.addObject();
            copy(t, o, "TermID", "Type", "TextCode", "Name", "DisplayedName", "TargetOid");
            String text = t.path("Text").asText("");
            if (!text.isBlank()) o.put("Text", text);
        }
        return wrap("terms", out);
    }

    // ---------------------------------------------------------------- internals

    private JsonNode elements(String pathTemplate, String oidOrHetu, String targetDate) {
        JsonNode root = get(pathTemplate, oidOrHetu, targetDate);
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode el : root.path("elements")) {
            out.add(compact(el));
        }
        return wrap("elements", out);
    }

    private JsonNode get(String pathTemplate, String a, String b) {
        long started = System.nanoTime();
        JsonNode body = client.get()
                .uri(DATA + pathTemplate, a, b)
                .retrieve()
                .body(JsonNode.class);
        log.debug("GET {} [{}, {}] -> {} ms", pathTemplate, a, b, (System.nanoTime() - started) / 1_000_000);
        return body == null ? mapper.createObjectNode() : body;
    }

    /** One policy element -> {OID, Type, TypeName, Name, StartDate, EffectiveDate, Attributes{k: v}}. */
    private ObjectNode compact(JsonNode el) {
        ObjectNode o = mapper.createObjectNode();
        copy(el, o, "OID", "Type");
        String type = el.path("Type").asText(null);
        if (type != null) o.put("TypeName", ontology.find(type).map(InsuranceOntology.Entity::display).orElse(type));
        JsonNode name = el.path("Attributes").path("Name").path("Value");
        if (name.isTextual()) o.put("Name", name.asText());
        copy(el, o, "StartDate", "EffectiveDate");

        ObjectNode attrs = o.putObject("Attributes");
        for (Iterator<Map.Entry<String, JsonNode>> it = el.path("Attributes").fields(); it.hasNext(); ) {
            var entry = it.next();
            String key = entry.getKey();
            JsonNode attr = entry.getValue();
            if (key.equals("Name")) continue;
            if (attr.path("transient").asBoolean(false)) continue;
            if (!KEEP.contains(key) && NOISE.matcher(key).matches()) continue;
            JsonNode value = attr.path("Value");
            if (value.isNull() || value.isMissingNode()) continue;
            String text = value.asText();
            if (text.isBlank()) continue;
            attrs.put(key, text);
        }
        return o;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String f : fields) {
            JsonNode v = from.path(f);
            if (v.isValueNode() && !v.isNull()) to.put(f, v.asText());
        }
    }

    private JsonNode wrap(String field, ArrayNode items) {
        ObjectNode root = mapper.createObjectNode();
        root.put("count", items.size());
        root.set(field, items);
        return root;
    }

    public String toJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
