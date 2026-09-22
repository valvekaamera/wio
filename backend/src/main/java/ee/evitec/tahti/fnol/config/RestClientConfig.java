package ee.evitec.tahti.fnol.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Plain-JSON REST clients for the two insurance back-ends the tools call. */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient tahtiRestClient(RestClient.Builder builder, TahtiProperties props) {
        return builder.clone()
                .baseUrl(props.baseUrl())
                .requestFactory(factory(props.timeout()))
                .build();
    }

    @Bean
    public RestClient pcpcRestClient(RestClient.Builder builder, PcpcProperties props) {
        return builder.clone()
                .baseUrl(props.baseUrl())
                .requestFactory(factory(props.timeout()))
                .build();
    }

    private static ClientHttpRequestFactory factory(Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(readTimeout));
    }
}
