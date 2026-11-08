package com.chatchat.api.runtime.coding.evidence;
import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingCsvAggregateVerifierTest {
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static Map<String,Object> chunk(byte[] b)throws Exception{return new LinkedHashMap<>(Map.of("assetId","asset","fileName","counts.csv","sha256",sha(b),"size",b.length,"chunkCount",1,"chunkIndex",0,"chunkSha256",sha(b),"contentBase64",Base64.getEncoder().encodeToString(b)));}
    @Test void originalCsvUsesExactDecimalArithmeticAndCsvQuoting() {
        var actual=CodingCsvAggregateVerifier.calculate(bytes("name,value\n\"a,b\",0.1\nc,0.2\nc,-0.05\n"),"value");
        assertThat(actual).containsEntry("rowCount",3L).containsEntry("sum","0.25");
    }
    @Test void missingAndNonNumericValuesCannotBecomeZeroOrFabricatedResults(){
        for(String csv:List.of("value\n\"\"\n","other\n5\n","value\nNaN\n","value\n1e3\n"))
            assertThatThrownBy(()->CodingCsvAggregateVerifier.calculate(bytes(csv),"value")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void originalChunksRequireHashesSizeAndOneSource()throws Exception {
        var b=bytes("value\n2\n");var row=chunk(b);assertThat(CodingCsvAggregateVerifier.originalCsv(List.of(row))).isEqualTo(b);
        row.put("chunkSha256","a".repeat(64));assertThatThrownBy(()->CodingCsvAggregateVerifier.originalCsv(List.of(row))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->CodingCsvAggregateVerifier.originalCsv(List.of(chunk(b),chunk(b)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void independentVerifierRejectsWrongSumCountAndSourceBinding()throws Exception {
        var source=bytes("value\n0.1\n0.2\n");var input=bytes(new ObjectMapper().writeValueAsString(Map.of("datasetReference","user-asset:asset","record",chunk(source)))+"\n");
        var port=mock(McpPythonControlPlanePort.class);when(port.downloadDataFile("t","u","input")).thenReturn(input);
        var verifier=new CodingCsvAggregateVerifier(port);var scope=new KernelDataScope("t","u",null,null,"run",null,Map.of());
        var snapshot=new CodingInputSnapshotService.Snapshot("snapshot","input","input.jsonl",sha(input),1,input.length,"source");
        var rules=Map.<String,Object>of("rowSchema",Map.of("type","object","additionalProperties",true),"valueField","value");
        var good=CodingCsvAggregateVerifier.calculate(source,"value");var row=Map.<String,Object>of("verifiedSummary",good,"analysisStatus","COMPLETE");
        var result=verifier.verify(scope,snapshot,rules,List.of(row));assertThat(result.passed()).isTrue();assertThat(result.businessEvaluated()).isTrue();
        for(var wrong:List.<Map<String,Object>>of(Map.of("sum","999"),Map.of("rowCount",5),Map.of("sourceSha256","b".repeat(64)))){
            var changed=new LinkedHashMap<>(good);changed.putAll(wrong);
            assertThat(verifier.verify(scope,snapshot,rules,List.of(Map.of("verifiedSummary",changed,"analysisStatus","COMPLETE"))).passed()).isFalse();
        }
        verify(port,times(4)).downloadDataFile("t","u","input");
    }
}
