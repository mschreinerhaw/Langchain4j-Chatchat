package com.chatchat.deployment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Independent control plane; deliberately has no Runtime OS business dependencies. */
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class DeploymentOpsApplication {
    @org.springframework.context.annotation.Bean
    org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> dataDiskDocumentRoot() {
        return factory->{String temporary=System.getProperty("OPS_VALIDATED_TMP");if(temporary!=null)try{factory.setDocumentRoot(com.chatchat.deployment.storage.StorageGuard.managedDirectory(java.nio.file.Path.of(temporary).resolve("tomcat-docbase")).toFile());}catch(java.io.IOException error){throw new IllegalStateException("Data-disk Tomcat document root unavailable",error);}};
    }
    public static void main(String[] args) throws Exception {
        com.chatchat.deployment.storage.StorageGuard.verify();
        java.nio.file.Path data=java.nio.file.Path.of(System.getenv().getOrDefault("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data"));
        java.nio.file.Path temporary=com.chatchat.deployment.storage.StorageGuard.managedDirectory(java.nio.file.Path.of(System.getenv().getOrDefault("OPS_TMP_DIR",data.toAbsolutePath().getParent().resolve("tmp").toString())));
        System.setProperty("java.io.tmpdir",temporary.toString());
        System.setProperty("OPS_VALIDATED_TMP",temporary.toString());
        System.setProperty("server.tomcat.basedir",com.chatchat.deployment.storage.StorageGuard.managedDirectory(temporary.resolve("tomcat")).toString());
        System.setProperty("spring.servlet.multipart.location",com.chatchat.deployment.storage.StorageGuard.managedDirectory(temporary.resolve("uploads")).toString());
        if(args.length>0 && args[0].equals("--import-package")) {
            com.chatchat.deployment.protocol.LocalPackageImport.run(args);
            return;
        }
        if(args.length==2 && args[0].equals("--resume-import")) {
            com.chatchat.deployment.protocol.LocalPackageImport.resume(args[1]);
            return;
        }
        if(args.length>0 && args[0].equals("--restore-backup")) {
            com.chatchat.deployment.backup.LocalRecovery.run(args);
            return;
        }
        if(args.length>0 && args[0].equals("--install-service")) {
            com.chatchat.deployment.runtime.ServiceInstaller.run(args);
            return;
        }
        if(args.length==2 && args[0].equals("--service-maintenance")) {
            com.chatchat.deployment.runtime.ServiceMaintenance.run(args[1]);
            return;
        }
        if(args.length==2 && args[0].equals("--recover-maintenance")) {
            com.chatchat.deployment.runtime.ServiceMaintenance.recover(args[1]);
            return;
        }
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
        try{application.run(args);}catch(Throwable failure){com.chatchat.deployment.runtime.StandaloneRuntime.close();throw failure;}
    }
}
