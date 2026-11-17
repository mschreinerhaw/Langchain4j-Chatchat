package com.chatchat.deployment.runtime;

import java.nio.file.*;
import java.nio.channels.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.security.*;
import org.springframework.core.io.ClassPathResource;

/** Extract bundled agent; never invokes a business stop during JVM shutdown. */
public final class StandaloneRuntime {
    private static FileChannel channel;
    private static FileLock lock;
    private StandaloneRuntime() {}
    public static void start() throws Exception {
        if(System.getenv("EXECUTOR_TOKEN")!=null||System.getenv("OPS_EXECUTOR_URL")!=null)return;
        Path root=Path.of(System.getenv().getOrDefault("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data")).toAbsolutePath().getParent().resolve("standalone");
        root=com.chatchat.deployment.storage.StorageGuard.managedDirectory(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        channel=FileChannel.open(root.resolve("process.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);lock=channel.tryLock();
        if(lock==null)throw new IllegalStateException("This standalone instance is already running");
        Properties config=new Properties();Path file=root.resolve("instance.properties");
        if(Files.exists(file)){try(var in=Files.newInputStream(file)){config.load(in);}}
        else{
            for(String key:List.of("EXECUTOR_TOKEN","OPS_DB_PASSWORD","OPS_INSTANCE_ID","OPS_SETUP_TOKEN"))config.setProperty(key,UUID.randomUUID().toString()+UUID.randomUUID());
            Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try(var out=Files.newOutputStream(file)){config.store(out,"Private local configuration; never distribute");}
        }
        config.forEach((k,v)->System.setProperty(k.toString(),v.toString()));
        String port=System.getenv().getOrDefault("OPS_EXECUTOR_PORT","19242");
        if(!port.matches("[0-9]{4,5}")||Integer.parseInt(port)>65535||Integer.parseInt(port)<1024)throw new IllegalArgumentException("Invalid local executor port");
        System.setProperty("OPS_EXECUTOR_URL","http://127.0.0.1:"+port);
        System.out.println("First-install authorization token: "+config.getProperty("OPS_SETUP_TOKEN"));
        Path trust=root.resolve("trust");Files.createDirectories(trust);
        for(String name:new ClassPathResource("standalone/index.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\\s+")){
            if(!name.startsWith("trust/"))continue;
            if(!name.matches("trust/[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Invalid bundled trust resource");
            Path key=trust.resolve(Path.of(name).getFileName());if(!Files.exists(key))Files.write(key,new ClassPathResource("standalone/"+name).getContentAsByteArray(),StandardOpenOption.CREATE_NEW);
        }
        var executor=new com.chatchat.deployment.execution.JdkExecutor(root.resolve("jdk"),trust,config,Integer.parseInt(port));
        System.setProperty("OPS_EXECUTOR_IMPLEMENTATION","JDK");
        Runtime.getRuntime().addShutdownHook(new Thread(executor::close));
    }
}
