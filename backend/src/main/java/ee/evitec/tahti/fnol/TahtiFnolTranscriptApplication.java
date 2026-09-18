package ee.evitec.tahti.fnol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TahtiFnolTranscriptApplication {

    public static void main(String[] args) {
        SpringApplication.run(TahtiFnolTranscriptApplication.class, args);
    }
}
