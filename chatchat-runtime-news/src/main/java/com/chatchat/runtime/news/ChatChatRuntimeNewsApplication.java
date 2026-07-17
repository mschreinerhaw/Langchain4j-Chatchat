package com.chatchat.runtime.news;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.chatchat.common", "com.chatchat.runtime.news"})
@ConfigurationPropertiesScan(basePackages = {"com.chatchat.common", "com.chatchat.runtime.news"})
@EntityScan(basePackages = "com.chatchat.runtime.news")
@EnableJpaRepositories(basePackages = "com.chatchat.runtime.news")
@EnableScheduling
public class ChatChatRuntimeNewsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatChatRuntimeNewsApplication.class, args);
    }
}
