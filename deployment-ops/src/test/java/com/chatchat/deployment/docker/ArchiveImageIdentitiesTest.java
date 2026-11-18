package com.chatchat.deployment.docker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveImageIdentitiesTest {
    @TempDir Path temporary;
    String digest(String value)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    Path archive(Map<String,String> members)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(var member:members.entrySet()){byte[] entry=TarArchive.singleFile(member.getKey(),member.getValue().getBytes(StandardCharsets.UTF_8),0600,0,0);out.write(entry,0,entry.length-1024);}
        out.write(new byte[1024]);return Files.write(temporary.resolve(UUID.randomUUID()+".tar"),out.toByteArray());
    }
    @Test void configAndManifestIdsAreLinkedByVerifiedContent()throws Exception{
        String config="{\"os\":\"linux\",\"architecture\":\"amd64\"}",configId=digest(config);
        String manifest="{\"config\":{\"digest\":\""+configId+"\"}}",manifestId=digest(manifest);
        Path path=archive(Map.of("index.json","{\"manifests\":[{\"digest\":\""+manifestId+"\"}]}","blobs/sha256/"+manifestId.substring(7),manifest,"blobs/sha256/"+configId.substring(7),config));
        assertEquals(Set.of(manifestId),ArchiveImageIdentities.read(path).get(configId));
    }
    @Test void aTamperedManifestCannotAuthorizeAnotherImage()throws Exception{
        String manifestId=digest("original");
        Path path=archive(Map.of("index.json","{\"manifests\":[{\"digest\":\""+manifestId+"\"}]}","blobs/sha256/"+manifestId.substring(7),"changed"));
        assertThrows(IOException.class,()->ArchiveImageIdentities.read(path));
    }
    @Test void classicArchiveNeedsNoOciMapping()throws Exception{assertTrue(ArchiveImageIdentities.read(archive(Map.of("manifest.json","[]"))).isEmpty());}
}
