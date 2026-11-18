package com.chatchat.deployment.backup;

import com.chatchat.deployment.execution.StateFiles;
import com.chatchat.deployment.protocol.SignedPackageStore;
import com.chatchat.deployment.storage.StorageGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import java.util.zip.*;
import static com.chatchat.deployment.protocol.InstallationManifest.require;

/** Offline restore only: exclusive platform lock, validate first, retain all replaced files. */
public final class LocalRecovery {
    private LocalRecovery(){}
    private static void extract(ZipFile zip,String member,Path target,long maximum)throws Exception {
        ZipEntry entry=zip.getEntry(member);require(entry!=null&&!entry.isDirectory()&&entry.getSize()>0&&entry.getSize()<=maximum,"Missing or oversized backup member: "+member);
        Files.createFile(target,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(var in=zip.getInputStream(entry);var out=Files.newOutputStream(target)){byte[] block=new byte[65536];long total=0;for(int n;(n=in.read(block))!=-1;){total+=n;require(total<=maximum&&total<=entry.getSize(),"Oversized backup member");out.write(block,0,n);}require(total==entry.getSize(),"Truncated backup member");}
    }
    private static void database(Path archive,String member,Path target)throws Exception {
        try(ZipFile zip=new ZipFile(archive.toFile())){require(zip.size()==1&&zip.getEntry(member)!=null,"Unexpected files in H2 backup");extract(zip,member,target,2L*1024*1024*1024);}
    }
    private static void replace(Path staged,Path destination)throws Exception {
        StorageGuard.managedDirectory(destination.getParent());require(!Files.isSymbolicLink(destination),"Recovery destination symlink forbidden");Files.move(staged,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public static void run(String[] args)throws Exception {
        require(args.length==3,"Usage: --restore-backup BACKUP_FILE SHA256 (platform must be stopped)");Path archive=Path.of(args[1]).toAbsolutePath().normalize();
        require(Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)&&archive.toRealPath().equals(archive),"Backup must be a readable regular file");require(args[2].matches("[a-f0-9]{64}")&&SignedPackageStore.sha(archive).equals(args[2]),"Backup SHA-256 mismatch");
        Path data=StorageGuard.managedDirectory(Path.of(com.chatchat.deployment.storage.StorageOptions.data().toString())),root=StorageGuard.managedDirectory(data.getParent().resolve("standalone"));
        try(FileChannel channel=FileChannel.open(root.resolve("process.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()) {
            require(lock!=null,"Platform is running; stop this platform instance before restoring. Business containers must stay running.");
            Path work=StorageGuard.managedDirectory(data.getParent().resolve("maintenance/restore-"+StateFiles.id()));Files.setPosixFilePermissions(work,PosixFilePermissions.fromString("rwx------"));
            JsonNode manifest;boolean execution;
            try(ZipFile zip=new ZipFile(archive.toFile())) {
                Set<String> names=new HashSet<>();for(ZipEntry entry:Collections.list(zip.entries()))require(names.add(entry.getName()),"Duplicate backup members");
                extract(zip,"manifest.json",work.resolve("manifest.json"),1024*1024);manifest=StateFiles.JSON.readTree(Files.readAllBytes(work.resolve("manifest.json")));execution=manifest.path("format").asText().equals("deployment-ops-h2/v2");
                require(execution||manifest.path("format").asText().equals("deployment-ops-h2/v1"),"Unsupported backup format");require(names.equals(execution?Set.of("manifest.json","database.zip","execution.zip","instance.properties"):Set.of("manifest.json","database.zip")),"Unexpected backup members");
                extract(zip,"database.zip",work.resolve("database.zip"),2L*1024*1024*1024);require(SignedPackageStore.sha(work.resolve("database.zip")).equals(manifest.path("databaseSha256").asText()),"Management database checksum mismatch");
                if(execution){require(data.toString().equals(manifest.path("dataDirectory").asText()),"Restore must use the same data directory; deployment paths are instance-bound");extract(zip,"execution.zip",work.resolve("execution.zip"),2L*1024*1024*1024);extract(zip,"instance.properties",work.resolve("instance.properties"),1024*1024);require(SignedPackageStore.sha(work.resolve("execution.zip")).equals(manifest.path("executionSha256").asText())&&SignedPackageStore.sha(work.resolve("instance.properties")).equals(manifest.path("configurationSha256").asText()),"Execution or configuration checksum mismatch");}
            }
            Properties config=new Properties();try(var in=Files.newInputStream(execution?work.resolve("instance.properties"):root.resolve("instance.properties"))){config.load(in);}String password=config.getProperty("OPS_DB_PASSWORD");require(password!=null&&!password.isBlank(),"Private H2 password missing");
            database(work.resolve("database.zip"),"deployment-ops.mv.db",work.resolve("deployment-ops.mv.db"));System.setProperty("OPS_DB_PASSWORD",password);BackupVerifier.prepare(work);
            if(execution){database(work.resolve("execution.zip"),"execution.mv.db",work.resolve("execution.mv.db"));try(Connection c=DriverManager.getConnection("jdbc:h2:file:"+work.resolve("execution")+";IFEXISTS=TRUE","deployment_ops",password);Statement s=c.createStatement()){s.executeQuery("SELECT COUNT(*) FROM execution_records").close();}}
            Path main=data.resolve("deployment-ops.mv.db"),journal=root.resolve("jdk/journal/execution.mv.db"),configuration=root.resolve("instance.properties");List<Path> destinations=new ArrayList<>(List.of(main));if(execution)destinations.addAll(List.of(journal,configuration));
            for(int i=0;i<destinations.size();i++){Path destination=destinations.get(i);require(!Files.isSymbolicLink(destination),"Recovery target symlink forbidden");if(Files.exists(destination))Files.copy(destination,work.resolve("before-"+i),StandardCopyOption.COPY_ATTRIBUTES);}
            try {replace(work.resolve("deployment-ops.mv.db"),main);if(execution){replace(work.resolve("execution.mv.db"),journal);replace(work.resolve("instance.properties"),configuration);}}
            catch(Exception failure){for(int i=0;i<destinations.size();i++){Path before=work.resolve("before-"+i);if(Files.exists(before))Files.copy(before,destinations.get(i),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);}throw failure;}
            System.out.println("Restore verified and installed. Previous files retained at "+work+". Start the platform JAR; business containers and volumes were not changed.");
        }
    }
}
