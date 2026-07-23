package com.chatchat.license.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.chatchat.license.server")
@ConfigurationPropertiesScan(basePackages = "com.chatchat.license.server")
public class LicenseCenterApplication {
    public static void main(String[] args) {
        SpringApplication.run(LicenseCenterApplication.class, args);
    }
}
