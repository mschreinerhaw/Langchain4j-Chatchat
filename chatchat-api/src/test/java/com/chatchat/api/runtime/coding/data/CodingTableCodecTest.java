package com.chatchat.api.runtime.coding.data;
import com.chatchat.common.runtime.coding.CodingInputManifest;
import com.chatchat.common.runtime.coding.CodingInputManifest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CodingTableCodecTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.Test void originalParquetFooterAndCsvHeaderRevealStructureWithoutSourceRows() throws Exception {
        Path parquet=temp.resolve("original.parquet");
        var schema=org.apache.parquet.schema.MessageTypeParser.parseMessageType(
            "message original { optional binary customer_id (STRING); optional double amount; }");
        try(var writer=org.apache.parquet.hadoop.example.ExampleParquetWriter.builder(new org.apache.parquet.io.LocalOutputFile(parquet))
                .withConf(new org.apache.parquet.conf.PlainParquetConfiguration()).withType(schema).build()) {
            writer.write(new org.apache.parquet.example.data.simple.SimpleGroupFactory(schema).newGroup()
                .append("customer_id","PRIVATE_RECORD_42").append("amount",1.25));
        }
        var metadata=CodingTableCodec.describeNative(parquet);
        assertThat(metadata).containsEntry("format","PARQUET").containsEntry("rowCount",1L).containsEntry("status","AVAILABLE");
        assertThat(metadata.get("schema").toString()).contains("customer_id","amount").doesNotContain("PRIVATE_RECORD_42","1.25");
        Path csv=temp.resolve("original.csv");Files.writeString(csv,"\"trade,id\",amount\nPRIVATE_RECORD_42,1.25\n");
        assertThat(CodingTableCodec.describeNative(csv)).containsEntry("columns",List.of("trade,id","amount"))
            .containsEntry("types","UNSPECIFIED_IN_CSV");
        assertThat(CodingTableCodec.describeNative(csv).toString()).doesNotContain("PRIVATE_RECORD_42","1.25");
        Files.write(parquet,new byte[]{1,2,3});
        assertThat(CodingTableCodec.describeNative(parquet)).containsEntry("status","STRUCTURE_UNAVAILABLE");
    }
    @ParameterizedTest @EnumSource(value=Format.class,names={"PARQUET","CSV"})
    void realFilesPreserveDecimalsUnicodeEmptyNullAndMissing(Format format) throws Exception {
        var json=new ObjectMapper();
        var columns=List.of(new Column("amount","c0",Type.DECIMAL,20,4,0,0),
            new Column("text","c1",Type.STRING,0,0,1,1));
        List<Map<String,Object>> data=new ArrayList<>();
        data.add(Map.of("amount",new BigDecimal("123456789012.3400"),"text","逗号,\"引号\"\n换行"));
        data.add(Map.of("amount",BigDecimal.ZERO,"text",""));
        var explicitNull=new LinkedHashMap<String,Object>();explicitNull.put("amount",BigDecimal.ONE);explicitNull.put("text",null);data.add(explicitNull);
        data.add(Map.of("amount",BigDecimal.TEN));data.add(Map.of("amount",BigDecimal.TEN,"text","\\N"));
        Path file=temp.resolve("input."+format.name().toLowerCase());
        CodingTableCodec.write(file,format,columns,data.stream().map(json::valueToTree).map(n->(com.fasterxml.jackson.databind.JsonNode)n).toList(),json);
        var manifest=new CodingInputManifest(CodingInputManifest.VERSION,"source",format,"c"+"a".repeat(63),"b".repeat(64),5,100,
            columns,List.of(new Part("c"+"d".repeat(63),file.getFileName().toString(),"b".repeat(64),5,Files.size(file))));
        var restored=new ArrayList<Map<String,Object>>();CodingTableCodec.read(file,manifest,json,restored::add);
        assertThat(restored.get(0).get("amount")).isEqualTo(new BigDecimal("123456789012.3400"));
        assertThat(restored.get(0).get("text")).isEqualTo(data.get(0).get("text"));
        assertThat(restored.get(1)).containsEntry("text","");assertThat(restored.get(2)).containsEntry("text",null);
        assertThat(restored.get(3)).doesNotContainKey("text");assertThat(restored.get(4)).containsEntry("text","\\N");
        if(format==Format.PARQUET)assertThat(Files.readAllBytes(file)).startsWith(new byte[]{'P','A','R','1'});
    }
}
