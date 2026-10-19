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
