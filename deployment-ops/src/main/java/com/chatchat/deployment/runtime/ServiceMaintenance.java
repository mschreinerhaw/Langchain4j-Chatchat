package com.chatchat.deployment.runtime;

import com.chatchat.deployment.execution.*;
import com.chatchat.deployment.backup.LocalRecovery;
import com.chatchat.deployment.protocol.SignedPackageStore;
import com.chatchat.deployment.storage.StorageGuard;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Detached JDK maintenance worker. Its scope is this platform's unit, JAR and H2 files only. */
public final class ServiceMaintenance {
    private ServiceMaintenance(){}
    private static Path data(){return Path.of(System.getenv().getOrDefault("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data")).toAbsolutePath().normalize();}
    private static Path state(){return data().getParent();}
    public static boolean active(){return Files.exists(state().resolve("maintenance/active-jdk.json"));}
    public static void checkStartup()throws Exception {
        Path active=state().resolve("maintenance/active-jdk.json");if(!Files.exists(active))return;
        String id=Files.readString(active);require(id.matches("[a-f0-9]{32}"),"Invalid maintenance lock; inspect recovery journal");JsonNode job=jobs().read(id+".json");
        require(!job.path("recovery").path("status").asText().equals("failed")&&Set.of("start_platform","health_check","recovering").contains(job.path("phase").asText()),"Platform maintenance is incomplete; use --recover-maintenance with this job ID");
    }
    private static StateFiles jobs()throws Exception{return new StateFiles(StorageGuard.managedDirectory(state().resolve("maintenance/jobs")));}
    public static Object list()throws Exception{return jobs().list(".");}
    private static String service(){String value=System.getenv().getOrDefault("OPS_PLATFORM_SERVICE","");require(value.matches("deployment-ops(?:-[a-z0-9-]+)?"),"JDK systemd service is not configured");return value;}
    private static String definition(String service)throws Exception {
        Path unit=Path.of("/etc/systemd/system/"+service+".service");require(Files.isRegularFile(unit,LinkOption.NOFOLLOW_LINKS),"Managed unit definition missing");String text=Files.readString(unit);
        require(text.contains("\nWorkingDirectory="+state()+"\n")&&text.contains("\nEnvironment=OPS_DATA_DIR="+data()+"\n")&&text.contains("\nEnvironment=OPS_PLATFORM_SERVICE="+service+"\n"),"Maintenance unit scope mismatch");
        require(text.lines().filter(line->line.startsWith("ExecStart=")).count()==1,"Ambiguous platform command");return text;
    }
    private static Path installedJar(String definition)throws Exception {
        String line=definition.lines().filter(s->s.startsWith("ExecStart=")).findFirst().orElseThrow();String prefix="ExecStart="+Path.of(System.getProperty("java.home"),"bin/java")+" -Djava.io.tmpdir="+state().resolve("tmp")+" -jar ";require(line.startsWith(prefix),"Unsupported platform command");Path jar=Path.of(line.substring(prefix.length())).toAbsolutePath().normalize();require(jar.getParent().equals(state().resolve("jars"))&&Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)&&jar.getFileName().toString().equals(SignedPackageStore.sha(jar)+".jar"),"Installed JAR is outside this instance or its checksum changed");return jar;
    }
    public static Object submit(JsonNode request,String actor)throws Exception {
        require(StandaloneRuntime.executor()!=null,"Maintenance requires the in-process JDK executor");String action=request.path("action").asText(),name=request.path("name").asText(),service=service();
        require(Set.of("upgrade","restore").contains(action)&&name.matches((action.equals("restore")?"backup-":"upgrade-")+"[a-f0-9-]{36}\\.zip"),"Invalid maintenance archive");require(request.path("confirmation").asText().equals(action.toUpperCase(Locale.ROOT)+":"+name),"Exact maintenance confirmation required");String definition=definition(service);Path current=installedJar(definition),archive=state().resolve(action.equals("restore")?"backups":"upgrades").resolve(name);require(Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS),"Maintenance archive missing");String expected=Files.readString(archive.resolveSibling(name+".sha256")).split("\\s+")[0];require(expected.matches("[a-f0-9]{64}")&&SignedPackageStore.sha(archive).equals(expected),"Maintenance archive checksum mismatch");
        try(AutoCloseable guard=StandaloneRuntime.executor().backupLock()){
            Path directory=StorageGuard.managedDirectory(state().resolve("maintenance"));Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));Path active=directory.resolve("active-jdk.json");require(!Files.exists(active),"Maintenance already active; inspect retained job before recovery");
            String id=StateFiles.id();ObjectNode job=StateFiles.JSON.createObjectNode().put("id",id).put("action",action).put("actor",actor).put("target",name).put("service",service).put("archive",archive.toString()).put("sha256",expected).put("status","queued").put("createdAt",StateFiles.now());jobs().write(id+".json",job);Files.writeString(active,id,StandardOpenOption.CREATE_NEW);
            List<String> command=List.of("/usr/bin/systemd-run","--unit=deployment-ops-jdk-maintenance-"+id,"--property=WorkingDirectory="+state(),"--setenv=OPS_DATA_DIR="+data(),"--setenv=OPS_PORT="+System.getenv().getOrDefault("OPS_PORT","19240"),"--setenv=OPS_PLATFORM_SERVICE="+service,"--setenv=PATH=/nonexistent",Path.of(System.getProperty("java.home"),"bin/java").toString(),"-jar",current.toString(),"--service-maintenance",id);
            Process worker=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(worker.getInputStream().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);if(worker.waitFor()!=0){Files.deleteIfExists(active);job.put("status","failed").put("error",output);jobs().write(id+".json",job);throw new IllegalStateException("Failed to start isolated maintenance worker");}return job;
        }
    }
    private static void systemctl(String... args)throws Exception {
        List<String> command=new ArrayList<>(List.of("/bin/systemctl"));command.addAll(List.of(args));Process child=new ProcessBuilder(command).redirectErrorStream(true).start();if(!child.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new IllegalStateException("systemctl command timeout; inspect the retained unit and journal");}String output=new String(child.getInputStream().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);require(child.waitFor()==0,"systemctl failed: "+output);
    }
    private static void health(String service,Path expectedJar)throws Exception {
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();String port=System.getenv().getOrDefault("OPS_PORT","19240");require(port.matches("[0-9]{4,5}"),"Invalid health port");
        int stable=0;for(int attempt=0;attempt<60;attempt++) {try{Process query=new ProcessBuilder("/bin/systemctl","show",service,"-p","MainPID","--value").redirectErrorStream(true).start();require(query.waitFor(5,java.util.concurrent.TimeUnit.SECONDS),"Cannot determine platform PID");String pid=new String(query.getInputStream().readNBytes(128),java.nio.charset.StandardCharsets.UTF_8).trim();boolean own=pid.matches("[1-9][0-9]*")&&ProcessHandle.of(Long.parseLong(pid)).map(process->process.isAlive()&&Arrays.asList(process.info().arguments().orElse(new String[0])).contains(expectedJar.toString())).orElse(false);if(!own){stable=0;Thread.sleep(1000);continue;}var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200&&StateFiles.JSON.readTree(response.body()).path("status").asText().equals("UP")){if(++stable>=3)return;}else stable=0;}catch(java.io.IOException unavailable){stable=0;}Thread.sleep(1000);}throw new IllegalStateException("Platform health failed after maintenance");
    }
    private static void atomicText(Path destination,String content)throws Exception {Path temporary=Files.createTempFile(destination.getParent(),"ops-maint-",".tmp");try{Files.writeString(temporary,content);Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-r--r--"));Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    private static Path releaseJar(Path archive,Path work)throws Exception {
        Path output=work.resolve("upgrade.jar");try(ZipFile zip=new ZipFile(archive.toFile())){ZipEntry metadata=zip.getEntry("release.json"),binary=zip.getEntry("lib/deployment-ops.jar");require(metadata!=null&&metadata.getSize()>0&&metadata.getSize()<=2*1024*1024&&binary!=null&&binary.getSize()>0&&binary.getSize()<=1024L*1024*1024,"Invalid offline release archive");JsonNode release;try(var in=zip.getInputStream(metadata)){release=StateFiles.JSON.readTree(in.readNBytes(2*1024*1024+1));}require(release.path("format").asText().equals("deployment-ops-native/v1"),"Unsupported release format");try(var in=zip.getInputStream(binary);var out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){byte[] block=new byte[65536];long total=0;for(int count;(count=in.read(block))!=-1;){total+=count;require(total<=binary.getSize(),"Oversized JAR");out.write(block,0,count);}require(total==binary.getSize(),"Truncated JAR");}require(SignedPackageStore.sha(output).equals(release.path("files").path("lib/deployment-ops.jar").asText()),"Release JAR checksum mismatch");}
        try(JarFile jar=new JarFile(output.toFile())){require(jar.stream().noneMatch(e->e.getName().endsWith(".py"))&&jar.getEntry("BOOT-INF/classes/com/chatchat/deployment/execution/JdkExecutor.class")!=null&&jar.getEntry("BOOT-INF/classes/com/chatchat/deployment/DeploymentOpsApplication.class")!=null&&jar.getManifest()!=null&&jar.getManifest().getMainAttributes().getValue("Main-Class").equals("org.springframework.boot.loader.launch.JarLauncher"),"Spring Boot application JAR required");}return output;
    }
    private static void progress(ObjectNode job,String phase)throws Exception{job.put("status","running").put("phase",phase).put("updatedAt",StateFiles.now());jobs().write(job.path("id").asText()+".json",job);}
    public static void run(String id)throws Exception {
        exclusive(()->execute(id));
    }
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void exclusive(Work work)throws Exception {
        Path directory=StorageGuard.managedDirectory(state().resolve("maintenance")),file=directory.resolve("worker.lock");require(!Files.isSymbolicLink(file),"Invalid maintenance worker lock");
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){require(lock!=null,"A maintenance worker is still running");work.run();}
    }
    private static void execute(String id)throws Exception {
        require(id.matches("[a-f0-9]{32}"),"Invalid maintenance ID");ObjectNode job=(ObjectNode)jobs().read(id+".json");String service=service();require(service.equals(job.path("service").asText())&&job.path("status").asText().equals("queued"),"Maintenance scope or state changed");Path active=state().resolve("maintenance/active-jdk.json");require(Files.readString(active).equals(id),"Maintenance lock mismatch");String old=definition(service);Path oldJar=installedJar(old),archive=Path.of(job.path("archive").asText()).toAbsolutePath().normalize();require(archive.getParent().equals(state().resolve(job.path("action").asText().equals("restore")?"backups":"upgrades"))&&SignedPackageStore.sha(archive).equals(job.path("sha256").asText()),"Archive changed before execution");
        Path work=StorageGuard.managedDirectory(state().resolve("maintenance/work-"+id));Files.setPosixFilePermissions(work,PosixFilePermissions.fromString("rwx------"));Path unit=Path.of("/etc/systemd/system/"+service+".service");List<Path> files=List.of(data().resolve("deployment-ops.mv.db"),state().resolve("standalone/jdk/journal/execution.mv.db"),state().resolve("standalone/instance.properties"));boolean stopped=false,snapshotted=false;
        try {
            progress(job,"validate");Path upgraded=null;if(job.path("action").asText().equals("upgrade")){Path candidate=releaseJar(archive,work);Path jars=StorageGuard.managedDirectory(state().resolve("jars"));upgraded=jars.resolve(SignedPackageStore.sha(candidate)+".jar");if(Files.exists(upgraded))require(SignedPackageStore.sha(upgraded).equals(SignedPackageStore.sha(candidate)),"Installed upgrade JAR changed");else Files.move(candidate,upgraded,StandardCopyOption.ATOMIC_MOVE);}
            // Allow the HTTP response to complete before stopping this platform unit.
            Thread.sleep(2000);progress(job,"stop_platform");systemctl("stop",service);stopped=true;
            try(FileChannel lockFile=FileChannel.open(state().resolve("standalone/process.lock"),StandardOpenOption.WRITE);FileLock lock=lockFile.tryLock()){require(lock!=null,"Another instance still holds the platform lock");for(int i=0;i<files.size();i++){require(Files.isRegularFile(files.get(i),LinkOption.NOFOLLOW_LINKS),"Missing platform snapshot file");Files.copy(files.get(i),work.resolve("before-"+i),StandardCopyOption.COPY_ATTRIBUTES);}Files.writeString(work.resolve("unit.before"),old);ObjectNode hashes=StateFiles.JSON.createObjectNode();for(int i=0;i<files.size();i++)hashes.put(Integer.toString(i),SignedPackageStore.sha(work.resolve("before-"+i)));hashes.put("unit",SignedPackageStore.sha(work.resolve("unit.before")));job.set("snapshotHashes",hashes);jobs().write(id+".json",job);snapshotted=true;}
            if(upgraded!=null){progress(job,"switch_jar");atomicText(unit,old.replace(" -jar "+oldJar+"\n"," -jar "+upgraded+"\n"));systemctl("daemon-reload");}
            else{progress(job,"restore_h2");LocalRecovery.run(new String[]{"--restore-backup",archive.toString(),job.path("sha256").asText()});}
            progress(job,"start_platform");systemctl("start",service);progress(job,"health_check");health(service,upgraded==null?oldJar:upgraded);job.put("status","succeeded").put("phase","completed");
        }catch(Exception error){job.put("status","failed").put("error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());if(stopped)try{systemctl("stop",service);if(snapshotted)for(int i=0;i<files.size();i++)Files.copy(work.resolve("before-"+i),files.get(i),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);atomicText(unit,old);systemctl("daemon-reload");job.put("phase","recovering");jobs().write(id+".json",job);systemctl("start",service);health(service,oldJar);job.set("recovery",StateFiles.JSON.createObjectNode().put("status","succeeded").put("message","Previous JAR, H2 files and instance configuration restored"));}catch(Exception recovery){job.set("recovery",StateFiles.JSON.createObjectNode().put("status","failed").put("error",recovery.getMessage()).put("snapshot",work.toString()));}
        }finally{job.put("finishedAt",StateFiles.now());jobs().write(id+".json",job);if(Files.readString(active).equals(id)&&!job.path("recovery").path("status").asText().equals("failed"))Files.delete(active);}
    }
    public static void recover(String id)throws Exception {
        exclusive(()->restoreSnapshot(id));
    }
    private static void restoreSnapshot(String id)throws Exception {
        require(id.matches("[a-f0-9]{32}"),"Invalid maintenance ID");Path active=state().resolve("maintenance/active-jdk.json");require(Files.exists(active)&&Files.readString(active).equals(id),"No matching incomplete maintenance");ObjectNode job=(ObjectNode)jobs().read(id+".json");String service=service();require(job.path("service").asText().equals(service),"Recovery service mismatch");
        if(!job.has("snapshotHashes")){require(Set.of("queued","validate","stop_platform").contains(job.path("phase").asText("queued")),"Snapshot is incomplete; inspect retained files before recovery");String unchanged=definition(service);Path jar=installedJar(unchanged);systemctl("stop",service);job.put("phase","recovering");job.remove("recovery");jobs().write(id+".json",job);systemctl("start",service);health(service,jar);job.put("status","failed").put("finishedAt",StateFiles.now());job.set("recovery",StateFiles.JSON.createObjectNode().put("status","succeeded").put("message","Operation stopped before snapshot; unchanged platform restarted"));jobs().write(id+".json",job);Files.delete(active);return;}
        Path work=StorageGuard.managedDirectory(state().resolve("maintenance/work-"+id));String old=Files.readString(work.resolve("unit.before"));require(old.contains("\nWorkingDirectory="+state()+"\n")&&old.contains("\nEnvironment=OPS_DATA_DIR="+data()+"\n")&&old.contains("\nEnvironment=OPS_PLATFORM_SERVICE="+service+"\n"),"Snapshot unit scope mismatch");installedJar(old);
        require(SignedPackageStore.sha(work.resolve("unit.before")).equals(job.path("snapshotHashes").path("unit").asText()),"Unit snapshot checksum mismatch");List<Path> files=List.of(data().resolve("deployment-ops.mv.db"),state().resolve("standalone/jdk/journal/execution.mv.db"),state().resolve("standalone/instance.properties"));
        for(int i=0;i<files.size();i++)require(Files.isRegularFile(work.resolve("before-"+i),LinkOption.NOFOLLOW_LINKS)&&SignedPackageStore.sha(work.resolve("before-"+i)).equals(job.path("snapshotHashes").path(Integer.toString(i)).asText()),"Recovery snapshot checksum mismatch");
        systemctl("stop",service);
        try(FileChannel lockFile=FileChannel.open(state().resolve("standalone/process.lock"),StandardOpenOption.WRITE);FileLock lock=lockFile.tryLock()) {
            require(lock!=null,"Another platform instance is still running");for(int i=0;i<files.size();i++){Path destination=files.get(i);StorageGuard.managedDirectory(destination.getParent());require(!Files.isSymbolicLink(destination),"Recovery target symlink forbidden");Path temporary=Files.createTempFile(destination.getParent(),"recovery-",".tmp");try{Files.copy(work.resolve("before-"+i),temporary,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
        }
        atomicText(Path.of("/etc/systemd/system/"+service+".service"),old);systemctl("daemon-reload");job.put("phase","recovering");job.remove("recovery");jobs().write(id+".json",job);systemctl("start",service);health(service,installedJar(old));job.put("status","failed").put("finishedAt",StateFiles.now());job.set("recovery",StateFiles.JSON.createObjectNode().put("status","succeeded").put("message","Operator restored the verified previous platform snapshot"));jobs().write(id+".json",job);Files.delete(active);System.out.println("Previous platform snapshot restored; business containers were not changed.");
    }
}
