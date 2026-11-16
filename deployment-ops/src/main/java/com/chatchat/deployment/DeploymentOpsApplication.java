package com.chatchat.deployment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent control plane; deliberately has no Runtime OS business dependencies. */
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class DeploymentOpsApplication {
    public static void main(String[] args) throws Exception {
        com.chatchat.deployment.storage.StorageGuard.verify();
        SpringApplication.run(DeploymentOpsApplication.class, args);
    }
}
