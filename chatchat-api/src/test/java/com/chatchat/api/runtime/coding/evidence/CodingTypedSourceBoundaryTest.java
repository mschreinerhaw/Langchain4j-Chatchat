package com.chatchat.api.runtime.coding.evidence;
import com.chatchat.api.runtime.coding.data.CodingTableCodec;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingInputManifest.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
class CodingTypedSourceBoundaryTest {
    @TempDir Path temp;
    @ParameterizedTest @EnumSource(value=Format.class,names={"PARQUET","CSV"})
    void sourceCopyGuardStillRejectsDecodedRecordsAndDocuments(Format format) throws Exception {
        var json=new ObjectMapper();List<Map<String,Object>> source=List.of(Map.of("text","original document ".repeat(200)),Map.of("text","second original"));
        var columns=List.of(new Column("text","c0",Type.STRING,0,0,0,0));Path file=temp.resolve("input."+format.name().toLowerCase());
        CodingTableCodec.write(file,format,columns,source.stream().map(json::valueToTree).map(n->(JsonNode)n).toList(),json);
        var manifest=new CodingInputManifest(CodingInputManifest.VERSION,"source",format,"c"+"a".repeat(63),"b".repeat(64),2,4000,
            columns,List.of(new Part("c"+"d".repeat(63),file.getFileName().toString(),"b".repeat(64),2,Files.size(file))));
        var boundary=new ComputedResultBoundary(json);CodingTableCodec.read(file,manifest,json,boundary::observeRow);
        assertThat(boundary.copiedSource(new ArrayList<>(source))).isTrue();
        assertThat(boundary.copiedSource(List.of(Map.of("copied",source.get(0).get("text"))))).isTrue();
        assertThat(boundary.copiedSource(List.of(Map.of("recordCount",2,"finding","Two documents were measured")))).isFalse();
    }
}
