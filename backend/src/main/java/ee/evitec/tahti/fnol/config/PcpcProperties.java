package ee.evitec.tahti.fnol.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Insurance ontology REST API (pcpc-rest-app).
 *
 * @param baseUrl   application root, e.g. {@code http://localhost:8080/pcpc-rest-app}
 * @param branch    product-model branch whose entity catalogue is used, e.g. {@code tahti4devTEST20260921}
 * @param cacheTtl  how long the (large, near-static) entity catalogue is cached in memory
 * @param timeout   per-request read timeout; the catalogue is ~0.5 MB
 */
@ConfigurationProperties(prefix = "app.pcpc")
public record PcpcProperties(String baseUrl, String branch, Duration cacheTtl, Duration timeout) {

    public PcpcProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8080/pcpc-rest-app";
        baseUrl = TahtiProperties.stripTrailingSlash(baseUrl);
        if (branch == null || branch.isBlank()) throw new IllegalArgumentException("app.pcpc.branch is required");
        if (cacheTtl == null) cacheTtl = Duration.ofHours(1);
        if (timeout == null) timeout = Duration.ofSeconds(30);
    }
}
