import com.chatchat.platform.runtime.coding.CodingSharedFiles;
import java.nio.file.*;
import java.io.IOException;

/** Linux container gate for the exact compiled shared-volume adapter; no Spring/model/database. */
public class SharedInputFilesystemSmoke {
    public static void main(String[] args) throws Exception {
        Path staging=Files.createTempDirectory("shared-input-smoke-");
        var store=new CodingSharedFiles(staging.resolve("shared"));
        String id="c"+"a".repeat(63);Path input=Files.writeString(staging.resolve("input"),"original");
        String hash=CodingSharedFiles.hash(input);
        Path file=store.publish("tenant","owner",id,"part.parquet",input,hash,8);
        if(!Files.readString(file).equals("original"))throw new AssertionError("Publication changed input");
        Files.writeString(input,"tampered");boolean rejected=false;
        try{store.publish("tenant","owner",id,"part.parquet",input,CodingSharedFiles.hash(input),8);}catch(IOException expected){rejected=true;}
        if(!rejected || !Files.readString(file).equals("original"))throw new AssertionError("Immutable identity was replaced");
        Path escape=Files.createDirectory(staging.resolve("escape"));
        Path link=store.directory("tenant","owner","c"+"b".repeat(63));Files.createSymbolicLink(link,escape);
        rejected=false;
        try{store.publish("tenant","owner","c"+"b".repeat(63),"part.csv",input,CodingSharedFiles.hash(input),8);}
        catch(IOException expected){rejected=true;}
        if(!rejected || Files.list(escape).findAny().isPresent())throw new AssertionError("Symlink escaped shared root");
        System.out.println("{\"scope\":\"Real Linux filesystem, compiled production adapter\",\"immutableReplay\":true,\"symlinkEscapeRejected\":true,\"status\":\"PASSED\"}");
    }
}
