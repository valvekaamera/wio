package ee.evitec.tahti.fnol.agent.tools;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import ee.evitec.tahti.fnol.policy.InsuranceOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Insurance ontology tools. The full entity catalogue (~1500 rows) is cached in
 * {@link InsuranceOntology}; the model only ever receives the slice it asks for.
 */
@Component
public class InsuranceOntologyTools {

    private static final Logger log = LoggerFactory.getLogger(InsuranceOntologyTools.class);

    private final InsuranceOntology ontology;
    private final ObjectMapper mapper;

    public InsuranceOntologyTools(InsuranceOntology ontology, ObjectMapper mapper) {
        this.ontology = ontology;
        this.mapper = mapper;
    }

    @Tool(description = "Resolve insurance entity type codes (e.g. '138.210', '112.160') to their Finnish names "
            + "and entity family (Turva, Riski, Korvauslaji, Rakennus, Irtain...). Accepts a comma-separated "
            + "list of ids. Policy tools already include TypeName, so call this only for codes seen without a name.")
    public String getEntityTypesByIds(
            @ToolParam(description = "Comma-separated type codes, e.g. '138.210,112.160'") String ids) {
        List<String> list = Arrays.stream(ids.split("[,;\\s]+")).filter(s -> !s.isBlank()).toList();
        List<InsuranceOntology.Entity> found = ontology.findAll(list);
        log.info("[tool] getEntityTypesByIds({}) -> {} of {} found", ids, found.size(), list.size());
        return toJson(found);
    }

    @Tool(description = "Search insurance entity types by Finnish name fragment (e.g. 'rikkoutuminen', "
            + "'irtaimisto', 'putkivuoto') to learn which type codes exist in the product model. Returns at "
            + "most 'limit' matches.")
    public String searchEntityTypesByName(
            @ToolParam(description = "Name fragment, case-insensitive") String query,
            @ToolParam(description = "Maximum number of matches, 1-50", required = false) Integer limit) {
        int max = limit == null ? 15 : Math.max(1, Math.min(50, limit));
        List<InsuranceOntology.Entity> found = ontology.search(query, max);
        log.info("[tool] searchEntityTypesByName('{}') -> {} matches", query, found.size());
        return toJson(found);
    }

    private String toJson(List<InsuranceOntology.Entity> entities) {
        try {
            var out = mapper.createArrayNode();
            for (var e : entities) {
                var o = out.addObject();
                o.put("id", e.id());
                o.put("name", e.display());
                o.put("family", e.type());
                o.put("subtype", e.subtype());
                if (e.businessline() != null) o.put("businessline", e.businessline());
            }
            return mapper.writeValueAsString(out);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
