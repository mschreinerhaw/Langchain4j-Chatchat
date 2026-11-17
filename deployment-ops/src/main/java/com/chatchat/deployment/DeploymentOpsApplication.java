package com.chatchat.deployment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent control plane; deliberately has no Runtime OS business dependencies. */
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class DeploymentOpsApplication {
    public static void main(String[] args) throws Exception {
        com.chatchat.deployment.storage.StorageGuard.verify();
        if(args.length==2 && args[0].equals("--verify-backup")) {
            com.chatchat.deployment.backup.BackupVerifier.verify(java.nio.file.Path.of(args[1]));
            return;
        }
        if(args.length==2 && args[0].equals("--prepare-restore")) {
            com.chatchat.deployment.backup.BackupVerifier.prepare(java.nio.file.Path.of(args[1]));
            return;
        }
        com.chatchat.deployment.runtime.StandaloneRuntime.start();
        SpringApplication application=new SpringApplication(DeploymentOpsApplication.class);
        application.addInitializers(context->com.chatchat.deployment.storage.StorageGuard.validateDatasource(context.getEnvironment()));
        application.run(args);
    }
}
