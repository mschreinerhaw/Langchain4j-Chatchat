package com.chatchat.platform.runtime.coding;

import com.chatchat.common.runtime.coding.CodingInputManifest;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Shared data-plane adapter using the existing Python per-owner immutable upload layout. No host path on wire. */
public final class CodingSharedFiles {
    private final Path root;
    public CodingSharedFiles(Path root) {
        this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();
    }
    public Path directory(String tenant,String owner,String fileId) throws IOException {
        CodingInputManifest.fileId(fileId);
        Path directory=root.resolve(component(tenant)).resolve(component(owner)).resolve("uploads").resolve(fileId);
        rejectLinks(directory);
        return directory;
    }
    public Path staging(String tenant,String owner) throws IOException {
        Path parent=directory(tenant,owner,"c"+"0".repeat(63)).getParent().getParent().resolve("staging");
        ensureDirectories(parent);return Files.createTempDirectory(parent,"capture-");
    }
    public Path verify(String tenant,String owner,String fileId,String fileName,String sha256,long size) throws IOException {
        CodingInputManifest.fileName(fileName);CodingInputManifest.hash(sha256);
        Path file=directory(tenant,owner,fileId).resolve(fileName);
        rejectLinks(file);
        if(size<0 || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)!=size || !hash(file).equals(sha256))
            throw new IOException("Shared immutable input integrity mismatch");
        try(var entries=Files.list(file.getParent())) {
            if(entries.count()!=1)throw new IOException("Shared input identity must contain exactly one file");
        }
        return file;
    }
    /** Publish a fully written local staging file once. An existing identity must match exactly. */
    public Path publish(String tenant,String owner,String fileId,String fileName,Path source,String sha256,long size) throws IOException {
        CodingInputManifest.fileName(fileName);CodingInputManifest.hash(sha256);
        if(Files.isSymbolicLink(source) || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)
                || Files.size(source)!=size || !hash(source).equals(sha256))throw new IOException("Invalid staged input");
        Path dir=directory(tenant,owner,fileId);
        if(Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return verify(tenant,owner,fileId,fileName,sha256,size);
        ensureDirectories(dir.getParent());
        Path temporary=Files.createTempDirectory(dir.getParent(),".publish-"), part=temporary.resolve(fileName);
        try {
            Files.copy(source,part);
            try(var channel=java.nio.channels.FileChannel.open(part,StandardOpenOption.WRITE)){channel.force(true);}
            // Published directories are nonempty: even on Unix an atomic rename cannot replace one.
            try {Files.move(temporary,dir,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException | DirectoryNotEmptyException raced) { }
            return verify(tenant,owner,fileId,fileName,sha256,size);
        } finally {Files.deleteIfExists(part);Files.deleteIfExists(temporary);}
    }
    private void ensureDirectories(Path directory) throws IOException {
        // Check configured ancestors as well as task components, including dangling symlinks.
        rejectLinks(directory);
        Files.createDirectories(directory);
        rejectLinks(directory);
    }
    private void rejectLinks(Path path) throws IOException {
        if(!path.normalize().startsWith(root))throw new IOException("Shared input path escapes root");
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink in shared input path");
    }
    private static String component(String value) {
        if(value==null || value.isBlank() || value.length()>256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid shared input scope");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String hash(Path path) throws IOException {
        try(var input=Files.newInputStream(path)) {
            var digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[64*1024];int read;
            while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
