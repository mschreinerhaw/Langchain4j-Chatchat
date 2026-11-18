package com.chatchat.deployment.backup;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

@Service
public class UpgradeService {
    private final Path root;
    public UpgradeService(Environment env){root=Path.of(env.getProperty("OPS_DATA_DIR",com.chatchat.deployment.storage.StorageOptions.data().toString())).toAbsolutePath().getParent().resolve("upgrades");}
    public Map<String,Object> receive(InputStream input,long length,String expected)throws Exception{
        if(length<1||length>1024L*1024*1024||expected==null||!expected.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Valid SHA-256 and 1 GiB maximum archive required");
        Files.createDirectories(root);String name="upgrade-"+UUID.randomUUID()+".zip";Path partial=root.resolve(name+".partial"),target=root.resolve(name);MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try{
            long total=0;byte[] buffer=new byte[65536];
            try(var output=Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW)){
                int read;while((read=input.read(buffer))!=-1){total+=read;if(total>length)throw new IllegalArgumentException("Archive length mismatch");digest.update(buffer,0,read);output.write(buffer,0,read);}
            }
            String actual=HexFormat.of().formatHex(digest.digest());if(total!=length||!actual.equals(expected))throw new IllegalArgumentException("Archive length or SHA-256 mismatch");
            Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);Files.writeString(root.resolve(name+".sha256"),actual+"  "+name+"\n",StandardOpenOption.CREATE_NEW);
            return Map.of("name",name,"sha256",actual,"size",total);
        }finally{Files.deleteIfExists(partial);}
    }
}
