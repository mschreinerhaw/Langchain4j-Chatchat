package com.chatchat.api.runtime.coding.data;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingInputManifest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CodingNativeSchemaBindingTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    String id(char value){return "c"+String.valueOf(value).repeat(63);}
    NativeFile file(String name,char key)throws Exception {
        byte[] raw=Files.readAllBytes(root.resolve(name));
        return new NativeFile(id(key),name,name,"user-asset:"+key,UserAssetService.hash(raw),raw.length);
    }
    List<Column> columns(){return List.of(new Column("trade_date","c0",Type.STRING,0,0,0,0),new Column("quote_code","c1",Type.STRING,0,0,0,0));}
    NativeFile part()throws Exception {
        CodingTableCodec.write(root.resolve("original-1.parquet"),Format.PARQUET,columns(),
            List.of(json.valueToTree(Map.of("trade_date","2026-11-02","quote_code","301218"))),json);
        return file("original-1.parquet",'2');
    }
    NativeFile manifest(NativeFile part,long size)throws Exception {
        var manifest=new CodingInputManifest(CodingInputManifest.VERSION,"source",Format.PARQUET,id('f'),"a".repeat(64),1,100,
            columns(),List.of(new Part(id('e'),"part-00000.parquet",part.sha256(),1,size)));
        Files.write(root.resolve("original-0.json"),json.writeValueAsBytes(manifest));return file("original-0.json",'1');
    }
    @Test void bindsTheOriginalColumnMapToItsPartHashInsteadOfOverlappingTransportColumns()throws Exception {
        var part=part();var manifest=manifest(part,part.sizeBytes());
        var bound=CodingInputFilePublisher.nativeSourceManifest(root,List.of(manifest,part),part,CodingTableCodec.describeNative(root.resolve(part.fileName())),json);
        assertThat(bound).containsEntry("status","BOUND").containsEntry("fileId",manifest.fileId()).containsEntry("targetFileId",part.fileId())
            .containsEntry("columns",columns()).containsEntry("virtualPath",manifest.virtualPath());
        // Both wrapper and original use c0, but only the original map can read trade_date.
        var wrapper=new Column("assetId","c0",Type.STRING,0,0,0,0);
        assertThat(((List<Column>)bound.get("columns")).get(0).name()).isNotEqualTo(wrapper.name());
        assertThat(bound.toString()).doesNotContain(id('e')); // No authority from the old manifest's file ID.
    }
    @Test void refusesWrongSizeAndAmbiguousOriginalManifestsWithoutInventingAColumnMap()throws Exception {
        var part=part();var manifest=manifest(part,part.sizeBytes()+1);var structure=CodingTableCodec.describeNative(root.resolve(part.fileName()));
        assertThat(CodingInputFilePublisher.nativeSourceManifest(root,List.of(manifest,part),part,structure,json))
            .containsEntry("status","SOURCE_MANIFEST_MISMATCH").doesNotContainKey("columns");
        manifest=manifest(part,part.sizeBytes());Files.copy(root.resolve(manifest.fileName()),root.resolve("original-2.json"));var duplicate=file("original-2.json",'3');
        assertThat(CodingInputFilePublisher.nativeSourceManifest(root,List.of(manifest,part,duplicate),part,structure,json))
            .containsEntry("status","AMBIGUOUS_SOURCE_MANIFEST").doesNotContainKey("columns");
    }
    @Test void changedManifestBytesAndUnrelatedEmptyJsonCannotGrantASchema()throws Exception {
        var part=part();var manifest=manifest(part,part.sizeBytes());Files.writeString(root.resolve(manifest.fileName()),"{} ");
        Files.writeString(root.resolve("empty.json")," ");var empty=file("empty.json",'3');
        assertThat(CodingInputFilePublisher.nativeSourceManifest(root,List.of(manifest,part,empty),part,CodingTableCodec.describeNative(root.resolve(part.fileName())),json))
            .containsEntry("status","NO_MATCHING_DELIVERED_SOURCE_MANIFEST").doesNotContainKey("columns");
    }
    @Test void real2943RowFailureFixtureBindsThirtyNineBusinessColumnsToTheActualNativeFile()throws Exception {
        String fixture=System.getProperty("coding.nativeManifestFixture");org.junit.jupiter.api.Assumptions.assumeTrue(fixture!=null);
        Files.copy(Path.of(fixture,"manifest.json"),root.resolve("original-0.json"));
        Files.copy(Path.of(fixture,"part-00000.parquet"),root.resolve("original-1.parquet"));
        var manifest=file("original-0.json",'1');var part=file("original-1.parquet",'2');
        var structure=CodingTableCodec.describeNative(root.resolve(part.fileName()));
        assertThat(structure).containsEntry("rowCount",2943L);
        var bound=CodingInputFilePublisher.nativeSourceManifest(root,List.of(manifest,part),part,structure,json);
        assertThat(bound).containsEntry("status","BOUND").containsEntry("partSha256","32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4");
        var columns=(List<Column>)bound.get("columns");assertThat(columns).hasSize(39);
        assertThat(columns).extracting(Column::name).contains("trade_date","quote_code","change_pct").doesNotContain("assetId","contentBase64");
    }
}
