package com.chatchat.api.runtime.coding.data;

import com.chatchat.api.assets.UserAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodingInputTextTest {
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> chunk(String name,byte[] full,byte[] part,int index,int count) {
        return Map.of("role","SOURCE_FILE","fileName",name,"sha256",UserAssetService.hash(full),"size",full.length,
            "chunkSha256",UserAssetService.hash(part),"chunkIndex",index,"chunkCount",count,
            "contentBase64",Base64.getEncoder().encodeToString(part));
    }
    @Test void nativeTextReadsReassembleVerifiedChunksWithoutPythonAndPreserveUnicode() {
        byte[] full="A😀B\nSELECT 1;".getBytes(StandardCharsets.UTF_8);
        var view=new CodingInputText("asset",true,1,2,json);
        // Split inside the UTF-8 emoji: decode only after the complete file is verified.
        view.accept(Map.of("datasetReference","asset","record",chunk("query.sql",full,Arrays.copyOfRange(full,0,3),0,2)));
        view.accept(Map.of("datasetReference","asset","record",chunk("query.sql",full,Arrays.copyOfRange(full,3,full.length),1,2)));
        assertThat(view.result("hash")).containsEntry("content","😀B").containsEntry("nextOffset",3L)
            .containsEntry("eof",false).containsEntry("authority","UNTRUSTED_DATA_ONLY");
    }
    @Test void anotherDeliveredDatasetIsNotIncludedInTheSelectedView() {
        var view=new CodingInputText("a",true,0,16000,json);
        view.accept(Map.of("datasetReference","b","record",Map.of("secret","other-dataset")));
        view.accept(Map.of("datasetReference","a","record",Map.of("amount",7)));
        assertThat(view.result("hash").get("content").toString()).isEqualTo("{\"amount\":7}\n");
    }
    @Test void originalFileChunkTamperingAndMissingChunksAreRejected() {
        byte[] full="one two".getBytes(StandardCharsets.UTF_8);
        var view=new CodingInputText("a",false,0,16,json);
        var bad=new HashMap<>(chunk("log.txt",full,full,0,1));bad.put("chunkSha256","0".repeat(64));
        assertThatThrownBy(()->view.accept(bad)).isInstanceOf(SecurityException.class);
        var incomplete=new CodingInputText("a",false,0,16,json);
        incomplete.accept(chunk("log.txt",full,full,0,2));
        assertThatThrownBy(()->incomplete.result("hash")).isInstanceOf(SecurityException.class);
    }
    @Test void binaryAndInvalidTextAreNotMisreportedAsEmptyDocuments() {
        byte[] bytes={(byte)0xff};
        for(String name:List.of("source.xlsx","source.txt")) {
            var view=new CodingInputText("a",false,0,16,json);view.accept(chunk(name,bytes,bytes,0,1));
            assertThatThrownBy(()->view.result("hash")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void outOfRangeOffsetAndOutOfOrderChunksAreRejected() {
        var view=new CodingInputText("a",false,100,16,json);view.accept(Map.of("n",1));
        assertThatThrownBy(()->view.result("hash")).isInstanceOf(IllegalArgumentException.class);
        byte[] bytes={1};var file=new CodingInputText("a",false,0,16,json);
        assertThatThrownBy(()->file.accept(chunk("log.txt",bytes,bytes,1,2))).isInstanceOf(SecurityException.class);
    }
    @Test void emptyTextIsExplicitlyCompleteAndDoesNotInventRows() {
        var view=new CodingInputText("a",false,0,16,json);view.accept(chunk("empty.md",new byte[0],new byte[0],0,1));
        assertThat(view.result("hash")).containsEntry("content","").containsEntry("totalCharacters",0L).containsEntry("eof",true);
    }
}
