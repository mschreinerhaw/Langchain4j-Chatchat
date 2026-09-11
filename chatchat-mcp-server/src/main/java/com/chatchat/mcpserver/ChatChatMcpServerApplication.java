package com.chatchat.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(basePackages = {
    "com.chatchat.common",
    "com.chatchat.agents.tool",
    "com.chatchat.tools",
    "com.chatchat.integration.mcp",
    "com.chatchat.runtime.mcp",
    "com.chatchat.runtime.market",
    "com.chatchat.knowledgebase",
    "com.chatchat.mcpserver"
}, excludeFilters = @ComponentScan.Filter(
    type = FilterType.REGEX,
    pattern = "com\\.chatchat\\.integration\\.mcp\\.service\\.directory\\.ConfiguredRemoteMcpServiceProvider"
))
@ConfigurationPropertiesScan(basePackages = {
    "com.chatchat.common",
    "com.chatchat.mcpserver"
})
@EntityScan(basePackages = {"com.chatchat.mcpserver", "com.chatchat.integration.mcp", "com.chatchat.knowledgebase"})
@EnableJpaRepositories(basePackages = {"com.chatchat.mcpserver", "com.chatchat.integration.mcp", "com.chatchat.knowledgebase"})
@EnableScheduling
public class ChatChatMcpServerApplication {

    /**
     * Performs the main operation.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        SpringApplication.run(ChatChatMcpServerApplication.class, args);
    }
}
