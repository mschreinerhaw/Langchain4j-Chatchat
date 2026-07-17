package com.chatchat.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "com.chatchat.common",
    "com.chatchat.agents.tool",
    "com.chatchat.tools",
    "com.chatchat.integration.mcp",
    "com.chatchat.runtime.mcp",
    "com.chatchat.mcpserver"
})
@ConfigurationPropertiesScan(basePackages = {
    "com.chatchat.common",
    "com.chatchat.mcpserver"
})
@EntityScan(basePackages = {"com.chatchat.mcpserver", "com.chatchat.integration.mcp"})
@EnableJpaRepositories(basePackages = {"com.chatchat.mcpserver", "com.chatchat.integration.mcp"})
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
