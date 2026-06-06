package com.chatchat.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
    "com.chatchat.agents",
    "com.chatchat.tools",
    "com.chatchat.mcpserver"
})
@ConfigurationPropertiesScan
@EnableScheduling
public class ChatChatMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatChatMcpServerApplication.class, args);
    }
}
