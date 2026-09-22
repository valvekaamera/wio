package ee.evitec.tahti.fnol.agent.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The FNOL tool-set as a single {@link ToolCallbackProvider}. The claim advisor passes it
 * to the chat client; adding {@code spring-ai-starter-mcp-server-webmvc} would expose the
 * same bean to remote MCP clients without further code.
 */
@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider fnolToolCallbackProvider(TahtiPolicyTools policyTools,
                                                         InsuranceOntologyTools ontologyTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(policyTools, ontologyTools)
                .build();
    }
}
