package com.chatchat.deployment.runtime;

import com.chatchat.deployment.protocol.SignedPackageStore;
import com.chatchat.deployment.storage.StorageGuard;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Optional operating-system service registration; the application also runs directly via java -jar. */
public final class ServiceInstaller {
    private ServiceInstaller(){}
    public static void run(String[] args)throws Exception {
        require(args.length==2&&args[1].matches("deployment-ops(?:-[a-z0-9-]+)?"),"Usage: --install-service deployment-ops[-instance]");String service=args[1];
        Path data=StorageGuard.managedDirectory(Path.of(com.chatchat.deployment.storage.StorageOptions.data().toString())),state=data.getParent();
        // Boot classes are loaded from a nested URL; the application command identifies the physical JAR.
        Path source=com.chatchat.deployment.storage.StorageBootstrap.jarPath();require(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)&&source.toString().endsWith(".jar"),"Installer JAR missing");
        Path unit=Path.of("/etc/systemd/system/"+service+".service");require(!Files.exists(unit,LinkOption.NOFOLLOW_LINKS),"Service already exists; explicit upgrade is required");
        String port=System.getenv().getOrDefault("OPS_PORT","19240"),node=System.getenv().getOrDefault("OPS_EXECUTOR_PORT","19242"),bind=System.getenv().getOrDefault("OPS_BIND","0.0.0.0");
        for(String value:List.of(port,node))require(value.matches("[0-9]{4,5}")&&Integer.parseInt(value)>=1024&&Integer.parseInt(value)<=65535,"Invalid service port");require(!port.equals(node)&&bind.matches("[A-Za-z0-9.:-]+"),"Invalid service endpoints");
        String java=Path.of(System.getProperty("java.home"),"bin/java").toString();for(String value:List.of(state.toString(),java))require(value.matches("[A-Za-z0-9_./:-]+"),"Unsupported service path");
        Path jars=StorageGuard.managedDirectory(state.resolve("jars")),target=jars.resolve(SignedPackageStore.sha(source)+".jar");
        if(Files.exists(target))require(SignedPackageStore.sha(target).equals(target.getFileName().toString().replace(".jar","")),"Installed JAR is corrupt");else{Path staged=Files.createTempFile(jars,"install-",".jar");try{Files.copy(source,staged,StandardCopyOption.REPLACE_EXISTING);require(SignedPackageStore.sha(staged).equals(SignedPackageStore.sha(source)),"JAR changed during installation");Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(staged);}}
        Path runtime=StorageGuard.managedDirectory(StorageGuard.runtimeDirectory());
        StorageGuard.managedDirectory(state.resolve("tmp"));String definition="[Unit]\nDescription=Deployment Ops JDK management platform\nAfter=network.target\nRequiresMountsFor="+state+" "+runtime+"\n\n[Service]\nType=simple\nWorkingDirectory="+state+"\nEnvironment=OPS_DATA_DIR="+data+"\nEnvironment=OPS_RUNTIME_DIR="+runtime+"\nEnvironment=OPS_REQUIRED_MOUNT="+com.chatchat.deployment.storage.StorageOptions.requiredMount()+"\nEnvironment=OPS_STORAGE_MOUNT="+StorageGuard.mountPoint(data)+"\nEnvironment=OPS_RUNTIME_MOUNT="+StorageGuard.mountPoint(runtime)+"\nEnvironment=OPS_PORT="+port+"\nEnvironment=OPS_EXECUTOR_PORT="+node+"\nEnvironment=OPS_BIND="+bind+"\nEnvironment=OPS_PLATFORM_SERVICE="+service+"\nEnvironment=PATH=/nonexistent\nExecStart="+java+" -Djava.io.tmpdir="+state.resolve("tmp")+" -jar "+target+"\nRestart=on-failure\nRestartSec=5\nSuccessExitStatus=143\nTimeoutStopSec=45\nKillMode=process\nUMask=0077\nNoNewPrivileges=true\nProtectSystem=strict\nReadWritePaths="+state+" "+runtime+"\n\n[Install]\nWantedBy=multi-user.target\n";
        Files.writeString(unit,definition,StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(unit,PosixFilePermissions.fromString("rw-r--r--"));
        systemctl("daemon-reload");systemctl("enable",service);systemctl("start",service);awaitHealth(port);
        System.out.println("Installed "+service+". Open http://SERVER:"+port+" and create the administrator using the first-install token in journalctl -u "+service+". No business service was stopped.");
    }
    private static void awaitHealth(String port)throws Exception {
        var client=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        for(int attempt=0;attempt<45;attempt++){try{var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(java.time.Duration.ofSeconds(2)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200&&com.chatchat.deployment.execution.StateFiles.JSON.readTree(response.body()).path("status").asText().equals("UP"))return;}catch(java.io.IOException unavailable){}Thread.sleep(1000);}throw new IllegalStateException("Installed platform failed health checks; inspect its retained unit and data directory");
    }
    private static void systemctl(String... args)throws Exception {List<String> command=new ArrayList<>(List.of("/bin/systemctl"));command.addAll(List.of(args));Process process=new ProcessBuilder(command).inheritIO().start();require(process.waitFor()==0,"systemctl failed; inspect the retained service definition before retrying");}
}
