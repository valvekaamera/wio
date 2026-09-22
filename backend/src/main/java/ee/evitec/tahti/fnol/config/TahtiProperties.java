package ee.evitec.tahti.fnol.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy data REST API (tahti-rest-app).
 *
 * @param baseUrl  application root, e.g. {@code http://localhost:8085/tahti-rest-app};
 *                 the {@code /tahti-data} resource path is appended by the client
 * @param timeout  per-request read timeout
 */
@ConfigurationProperties(prefix = "app.tahti")
public record TahtiProperties(String baseUrl, Duration timeout) {

    public TahtiProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8085/tahti-rest-app";
        baseUrl = stripTrailingSlash(baseUrl);
        if (timeout == null) timeout = Duration.ofSeconds(15);
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
