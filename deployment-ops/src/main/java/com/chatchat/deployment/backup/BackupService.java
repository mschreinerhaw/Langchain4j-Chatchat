package com.chatchat.deployment.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import java.io.*;

@Service
public class BackupService {
    private final JdbcTemplate db;private final ObjectMapper json;private final Path root,data;
    public BackupService(JdbcTemplate db,ObjectMapper json,Environment env){this.db=db;this.json=json;data=Path.of(env.getProperty("OPS_DATA_DIR","/mnt/disk01/deployment-ops/data")).toAbsolutePath().normalize();root=data.getParent().resolve("backups");}
    public Path file(String name) throws IOException {
        if(!name.matches("backup-[a-f0-9-]{36}\\.zip"))throw new IllegalArgumentException("Invalid backup name");
        Path target=root.resolve(name);if(!Files.isRegularFile(target)||Files.isSymbolicLink(target))throw new IllegalArgumentException("Backup not found");return target;
    }
    public List<Map<String,Object>> list() throws IOException {
        if(!Files.isDirectory(root))return List.of();
        var records=new ArrayList<Map<String,Object>>();
        try(var files=Files.list(root)){for(Path file:files.filter(p->p.getFileName().toString().matches("backup-[a-f0-9-]{36}\\.zip")).toList()){
            records.add(Map.of("name",file.getFileName().toString(),"size",Files.size(file),"createdAt",Files.getLastModifiedTime(file).toInstant().toString(),"status","succeeded"));
        }}records.sort(Comparator.comparing(v->v.get("createdAt").toString(),Comparator.reverseOrder()));return records;
    }
    public synchronized Map<String,Object> create() throws Exception {
        Files.createDirectories(root);String name="backup-"+UUID.randomUUID()+".zip";Path temporary=root.resolve(name+".db.tmp"),execution=root.resolve(name+".execution.tmp"),partial=root.resolve(name+".partial"),target=root.resolve(name);
        var executor=com.chatchat.deployment.runtime.StandaloneRuntime.executor();
        try{Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        try(AutoCloseable guard=executor==null?()->{}:executor.backupLock()){
            db.execute("BACKUP TO '"+temporary.toString().replace("'","''")+"'");
            String databaseHash=digest(temporary);
            var manifest=new LinkedHashMap<String,Object>(Map.of("format","deployment-ops-h2/v1","schemaVersion",db.queryForObject("SELECT MAX(version) FROM schema_version",Integer.class),"h2Version",db.queryForObject("SELECT H2VERSION()",String.class),"createdAt",Instant.now().toString(),"databaseSha256",databaseHash,"scope","H2 management records; application data and executor journals are separate"));
            Path configuration=root.getParent().resolve("standalone/instance.properties");
            if(executor!=null){executor.backupJournal(execution);manifest.put("format","deployment-ops-h2/v2");manifest.put("executionSha256",digest(execution));manifest.put("configurationSha256",digest(configuration));manifest.put("dataDirectory",data.toString());manifest.put("scope","Management H2, execution H2 and private instance configuration; business volumes and offline media are separate");}
            try(var output=new ZipOutputStream(Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW))){
                output.putNextEntry(new ZipEntry("manifest.json"));output.write(json.writeValueAsBytes(manifest));output.closeEntry();
                output.putNextEntry(new ZipEntry("database.zip"));Files.copy(temporary,output);output.closeEntry();
                if(executor!=null){output.putNextEntry(new ZipEntry("execution.zip"));Files.copy(execution,output);output.closeEntry();output.putNextEntry(new ZipEntry("instance.properties"));Files.copy(configuration,output);output.closeEntry();}
            }
            try{Files.setPosixFilePermissions(partial,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);
            String hash=digest(target);
            Files.writeString(root.resolve(name+".sha256"),hash+"  "+name+"\n",StandardOpenOption.CREATE_NEW);
            return Map.of("name",name,"sha256",hash,"size",Files.size(target),"status","succeeded");
        }finally{Files.deleteIfExists(temporary);Files.deleteIfExists(execution);Files.deleteIfExists(partial);}
    }
    private String digest(Path path)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        try(var input=new java.security.DigestInputStream(Files.newInputStream(path),digest)){input.transferTo(OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
}
