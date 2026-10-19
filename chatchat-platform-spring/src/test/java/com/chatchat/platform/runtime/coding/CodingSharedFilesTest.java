package com.chatchat.platform.runtime.coding;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CodingSharedFilesTest {
    @TempDir Path temp;
    @Test void publicationIsImmutableScopedAndRejectsCorruption() throws Exception {
        var store=new CodingSharedFiles(temp.resolve("shared"));
        Path source=Files.writeString(temp.resolve("staged"),"original");String hash=CodingSharedFiles.hash(source),id="c"+"a".repeat(63);
        Path target=store.publish("tenant","owner",id,"part.parquet",source,hash,8);
        assertThat(store.publish("tenant","owner",id,"part.parquet",source,hash,8)).isEqualTo(target);
        assertThatThrownBy(()->store.verify("other","owner",id,"part.parquet",hash,8)).hasMessageContaining("integrity");
        Files.writeString(source,"tampered");
        assertThatThrownBy(()->store.publish("tenant","owner",id,"part.parquet",source,CodingSharedFiles.hash(source),8)).hasMessageContaining("integrity");
        assertThat(Files.readString(target)).isEqualTo("original");
        assertThatThrownBy(()->store.directory("tenant","owner","../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.verify("tenant","owner",id,"../secret",hash,8)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void manifestsValidateFormatCountsAndPartUniqueness() {
        String id="c"+"b".repeat(63),schema="c"+"d".repeat(63),hash="a".repeat(64);
        var part=new CodingInputManifest.Part(id,"part.parquet",hash,2,50);
        var manifest=new CodingInputManifest(CodingInputManifest.VERSION,"source",CodingInputManifest.Format.PARQUET,
            schema,hash,2,20,List.of(new CodingInputManifest.Column("amount","c0",CodingInputManifest.Type.DECIMAL,10,2,0,1)),List.of(part));
        assertThat(manifest.format()).isEqualTo(CodingInputManifest.Format.PARQUET);
        assertThatThrownBy(()->new CodingInputManifest(CodingInputManifest.VERSION,"source",CodingInputManifest.Format.CSV,
            schema,hash,2,20,List.of(),List.of(part))).hasMessageContaining("format");
        assertThatThrownBy(()->new CodingInputManifest(CodingInputManifest.VERSION,"source",CodingInputManifest.Format.PARQUET,
            schema,hash,3,20,List.of(),List.of(part))).hasMessageContaining("row count");
    }
    @Test void concurrentPublishersCannotReplaceEachOthersContent() throws Exception {
        var store=new CodingSharedFiles(temp.resolve("concurrent"));String id="c"+"e".repeat(63);
        var start=new java.util.concurrent.CountDownLatch(1);var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(String text:List.of("first","other")) {
                Path staged=Files.writeString(temp.resolve(text),text);
                jobs.add(executor.submit(()->{start.await();try{store.publish("tenant","owner",id,"part.csv",staged,CodingSharedFiles.hash(staged),5);return true;}
                    catch(java.io.IOException conflict){return false;}}));
            }
            start.countDown();int accepted=0;for(var job:jobs)if(job.get(10,java.util.concurrent.TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);assertThat(Files.readString(store.directory("tenant","owner",id).resolve("part.csv"))).isIn("first","other");
        }finally{executor.shutdownNow();}
    }
}
