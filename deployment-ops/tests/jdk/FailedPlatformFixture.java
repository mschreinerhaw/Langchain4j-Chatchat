import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;

/** Real failed-upgrade artifact: valid Boot JAR with an invalid server-port configuration. */
public class FailedPlatformFixture {
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]),target=Path.of(args[1]);boolean changed=false;
        try(JarFile jar=new JarFile(source.toFile());JarOutputStream out=new JarOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW))) {
            for(JarEntry original:Collections.list(jar.entries())) {
                byte[] bytes;try(var in=jar.getInputStream(original)){bytes=in.readAllBytes();}
                if(original.getName().equals("BOOT-INF/classes/application.yml")) {
                    String text=new String(bytes,StandardCharsets.UTF_8),replacement=text.replace("  port: ${OPS_PORT:19240}","  port: definitely-invalid-port");
                    if(text.equals(replacement))throw new IllegalArgumentException("Expected source configuration missing");bytes=replacement.getBytes(StandardCharsets.UTF_8);changed=true;
                }
                ZipEntry entry=new ZipEntry(original.getName());entry.setTime(0);
                if(original.getName().startsWith("BOOT-INF/lib/")){CRC32 crc=new CRC32();crc.update(bytes);entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);entry.setCompressedSize(bytes.length);entry.setCrc(crc.getValue());}
                out.putNextEntry(entry);out.write(bytes);out.closeEntry();
            }
        }
        if(!changed)throw new IllegalStateException("Failure fixture was not produced");System.out.println("Created real invalid-port upgrade JAR at "+target);
    }
}
