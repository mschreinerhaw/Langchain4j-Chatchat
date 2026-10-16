package com.chatchat.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;

/**
 * ChatChat Enterprise AI Application - Main Entry Point
 *
 * Enables:
 * - Async processing for long-running operations
 * - Transaction management for database operations
 * - Component scanning for all modules
 * - OpenAPI/Swagger documentation
 */
@SpringBootApplication(scanBasePackages = "com.chatchat")
// The MCP server's plain JAR is a test fixture dependency for Sandbox integration.
// Its independently hosted services/controllers must never become API components.
@ComponentScan(basePackages = "com.chatchat", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = {"com\\.chatchat\\.mcpserver\\..*", "com\\.chatchat\\.license\\.server\\..*"})
})
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@EntityScan(basePackages = "com.chatchat")
@EnableJpaRepositories(basePackages = "com.chatchat", excludeFilters =
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.chatchat\\.mcpserver\\..*"))
public class ChatChatApplication {

    /**
     * Performs the main operation.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        SpringApplication.run(ChatChatApplication.class, args);
    }
}
