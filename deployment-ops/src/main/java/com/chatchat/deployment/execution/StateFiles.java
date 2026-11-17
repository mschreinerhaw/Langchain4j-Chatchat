package com.chatchat.deployment.execution;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import com.chatchat.deployment.storage.StorageGuard;

/** Private atomic execution journals, independent from platform sessions. */
public final class StateFiles {
    public static final ObjectMapper JSON=new ObjectMapper();
    private final Path root;
    public StateFiles(Path root)throws IOException{this.root=root.toAbsolutePath().normalize();StorageGuard.verifyRuntime();Files.createDirectories(this.root);}
    public Path path(String name){Path value=root.resolve(name).normalize();if(!value.startsWith(root)||name.contains("..")||name.startsWith("/"))throw new IllegalArgumentException("Invalid state path");return value;}
    public synchronized void write(String name,JsonNode value)throws IOException{
        StorageGuard.verifyRuntime();Path destination=path(name);Files.createDirectories(destination.getParent());
        Path temporary=Files.createTempFile(destination.getParent(),"state-",".tmp");
        try{try{Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){ByteBuffer bytes=ByteBuffer.wrap(JSON.writeValueAsBytes(value));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temporary);}
    }
    public synchronized JsonNode read(String name)throws IOException{StorageGuard.verifyRuntime();return JSON.readTree(Files.readAllBytes(path(name)));}
    public synchronized List<JsonNode> list(String folder)throws IOException{
        StorageGuard.verifyRuntime();Path directory=path(folder);if(!Files.isDirectory(directory))return List.of();
        try(var files=Files.list(directory)){List<JsonNode> result=new ArrayList<>();for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList())result.add(JSON.readTree(Files.readAllBytes(file)));return result;}
    }
    public static String id(){return UUID.randomUUID().toString().replace("-","");}
    public static double now(){return System.currentTimeMillis()/1000.0;}
}
