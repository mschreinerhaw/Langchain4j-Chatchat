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
        Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
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
        Map<String,byte[]> content=new LinkedHashMap<>();MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String name:new ClassPathResource("standalone/index.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\\s+")){
            if(name.isBlank())continue;
            if(!name.matches("(executor|trust)/[a-zA-Z0-9_.-]+"))throw new IllegalArgumentException("Invalid bundled resource");
            byte[] bytes=new ClassPathResource("standalone/"+name).getContentAsByteArray();content.put(name,bytes);hash.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(bytes);
        }
        Path home=root.resolve("payload-"+HexFormat.of().formatHex(hash.digest()));
        for(var e:content.entrySet()){Path target=home.resolve(e.getKey());Files.createDirectories(target.getParent());Files.write(target,e.getValue());}
        Map<String,String> env=new HashMap<>();config.forEach((k,v)->env.put(k.toString(),v.toString()));
        for(String name:List.of("state","packages","apps"))Files.createDirectories(root.resolve(name));
        Path trust=root.resolve("trust");Files.createDirectories(trust);
        for(var entry:content.entrySet())if(entry.getKey().startsWith("trust/")){
            Path key=trust.resolve(Path.of(entry.getKey()).getFileName());if(!Files.exists(key))Files.write(key,entry.getValue(),StandardOpenOption.CREATE_NEW);
        }
        env.put("OPS_STATE",root.resolve("state").toString());env.put("OPS_PACKAGES",root.resolve("packages").toString());env.put("OPS_APP_DATA",root.resolve("apps").toString());env.put("OPS_TRUST",trust.toString());
        env.put("OPS_NATIVE","1");env.put("OPS_EXECUTOR_BIND","127.0.0.1");env.put("OPS_EXECUTOR_PORT",port);env.put("OPS_RUNTIME_ROOT","/mnt/disk01/ops/r");env.put("OPS_READ_ONLY","0");Files.createDirectories(Path.of("/mnt/disk01/ops/r"));
        ProcessBuilder builder=new ProcessBuilder(System.getenv().getOrDefault("OPS_PYTHON","python3"),home.resolve("executor/agent.py").toString());builder.environment().putAll(env);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("executor.log").toFile()));
        try{Process child=builder.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{child.destroy();try{child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}));}
        catch(java.io.IOException unavailable){System.err.println("Executor unavailable; install Python 3.11+ and restart. Platform remains available.");}
    }
}
