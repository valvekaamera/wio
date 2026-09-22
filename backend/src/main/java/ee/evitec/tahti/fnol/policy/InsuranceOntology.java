package ee.evitec.tahti.fnol.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import ee.evitec.tahti.fnol.config.PcpcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * In-memory cache of the pcpc entity catalogue ({@code /branch/{branch}/entities}).
 * The catalogue is ~1500 entries / ~0.5 MB and changes only when the product model is
 * released, so it is fetched once, indexed by id ("138.210") and served in slices:
 * the LLM never sees the whole thing, only the entries it asks about or that the policy
 * tools enrich their output with.
 */
@Component
public class InsuranceOntology {

    private static final Logger log = LoggerFactory.getLogger(InsuranceOntology.class);

    /** One row of the catalogue; only the fields that carry meaning for claim handling. */
    public record Entity(String id, String type, String subtype, String cleanName, String name, String businessline) {
        /** e.g. {@code Turva / Irtaimiston Tähtiturva} — type family plus Finnish subtype name. */
        public String display() {
            String family = stripCode(type);
            return family.equals(cleanName) ? cleanName : family + " / " + cleanName;
        }

        private static String stripCode(String s) {
            if (s == null) return "";
            int i = s.lastIndexOf(" (");
            return i > 0 ? s.substring(0, i) : s;
        }
    }

    private record Snapshot(Map<String, Entity> byId, Instant loadedAt) {}

    private final RestClient client;
    private final PcpcProperties props;
    private volatile Snapshot snapshot;

    public InsuranceOntology(@Qualifier("pcpcRestClient") RestClient client, PcpcProperties props) {
        this.client = client;
        this.props = props;
    }

    public Optional<Entity> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(entities().get(id.trim()));
    }

    /** Finnish subtype name for a type code, or the code itself when unknown/unavailable. */
    public String typeName(String id) {
        return find(id).map(Entity::cleanName).orElse(id);
    }

    public List<Entity> findAll(Collection<String> ids) {
        Map<String, Entity> all = entities();
        var out = new ArrayList<Entity>();
        for (String id : ids) {
            Entity e = all.get(id.trim());
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Case-insensitive substring search over Finnish names; used when the LLM knows a word, not a code. */
    public List<Entity> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        var out = new ArrayList<Entity>();
        if (q.isEmpty()) return out;
        for (Entity e : entities().values()) {
            if ((e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(q))
                    || (e.cleanName() != null && e.cleanName().toLowerCase(Locale.ROOT).contains(q))) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    public int size() {
        return entities().size();
    }

    /** Drop the cache so the next lookup re-fetches (e.g. after a product-model release). */
    public void invalidate() {
        snapshot = null;
    }

    // ---------------------------------------------------------------- loading

    private Map<String, Entity> entities() {
        Snapshot s = snapshot;
        if (s == null || isExpired(s)) {
            synchronized (this) {
                s = snapshot;
                if (s == null || isExpired(s)) {
                    try {
                        s = new Snapshot(load(), Instant.now());
                        snapshot = s;
                    } catch (RuntimeException e) {
                        if (s != null) {
                            log.warn("Ontology refresh failed, serving stale catalogue: {}", e.toString());
                        } else {
                            log.error("Ontology catalogue unavailable: {}", e.toString());
                            return Map.of();
                        }
                    }
                }
            }
        }
        return s.byId();
    }

    private boolean isExpired(Snapshot s) {
        Duration ttl = props.cacheTtl();
        return !ttl.isZero() && !ttl.isNegative() && s.loadedAt().plus(ttl).isBefore(Instant.now());
    }

    private Map<String, Entity> load() {
        long started = System.nanoTime();
        JsonNode root = client.get()
                .uri("/branch/{branch}/entities", props.branch())
                .retrieve()
                .body(JsonNode.class);
        var map = new LinkedHashMap<String, Entity>();
        if (root != null) {
            for (JsonNode n : root.path("entities")) {
                String id = n.path("id").asText(null);
                if (id == null) continue;
                map.put(id, new Entity(id,
                        n.path("type").asText(null),
                        n.path("subtype").asText(null),
                        n.path("cleanName").asText(null),
                        n.path("name").asText(null),
                        n.path("businessline").asText(null)));
            }
        }
        log.info("Loaded insurance ontology: {} entities from branch {} in {} ms",
                map.size(), props.branch(), (System.nanoTime() - started) / 1_000_000);
        return map;
    }
}
