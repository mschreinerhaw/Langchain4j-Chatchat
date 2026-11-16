package com.chatchat.deployment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent control plane; deliberately has no Runtime OS business dependencies. */
@SpringBootApplication
public class DeploymentOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeploymentOpsApplication.class, args);
    }
}
