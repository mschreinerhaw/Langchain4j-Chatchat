package com.chatchat.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * ChatChat Enterprise AI Application - Main Entry Point
 *
 * Enables:
 * - Async processing for long-running operations
 * - Transaction management for database operations
 * - Component scanning for all modules
 * - OpenAPI/Swagger documentation
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@EntityScan(basePackages = "com.chatchat")
@EnableJpaRepositories(basePackages = "com.chatchat")
@ComponentScan(basePackages = "com.chatchat")
public class ChatChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatChatApplication.class, args);
    }
}
