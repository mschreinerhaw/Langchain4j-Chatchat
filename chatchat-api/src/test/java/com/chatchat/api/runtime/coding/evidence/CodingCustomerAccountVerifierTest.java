package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.runtime.coding.data.CodingInputSnapshotService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingCustomerAccountVerifierTest {
    static final String CUSTOMER="0007",DATE="20260731";
    static Map<String,Object> asset(){return new LinkedHashMap<>(Map.of("KHH",CUSTOMER,"RQ",DATE,"ZZC","10.3","ZJYE","0.3","ZQSZ","10","DRYK","0.2"));}
    static Map<String,Object> holding(){return new LinkedHashMap<>(Map.of("KHH",CUSTOMER,"ZXSZ","10","CCCB","9.7","DRYK","0.1","LJYK","-0.2","ZQSL","2"));}
    static Map<String,Object> order(String price){return new LinkedHashMap<>(Map.of("KHH",CUSTOMER,"WTRQ",20260731,"CJSL",1,"CJJG",price,"WTH",price));}
    static Map<String,Object> row(String ref,Map<String,Object> record){return Map.of("datasetReference",ref,"record",record);}
    static List<Map<String,Object>> rows(){return List.of(row("asset",asset()),row("holding",holding()),row("orders",order("0.1")),row("orders",order("0.2")));}
    static Map<String,Object> declaration(String ref,Map<String,Object> sample,int count){
        var fields=new LinkedHashMap<String,Object>();sample.keySet().forEach(k->fields.put(k,Map.of("description",k)));
        return new LinkedHashMap<>(Map.of("datasetReference",ref,"rowCount",count,"fieldSchema",fields,"fieldDefinitions",fields,
            "templateParameterBinding",Map.of("effectiveParameters",Map.of("khh",CUSTOMER,"ksrq",DATE,"jsrq",DATE))));
    }
    static List<Map<String,Object>> versions(){return new ArrayList<>(List.of(declaration("asset",asset(),1),declaration("holding",holding(),1),declaration("orders",order("0.1"),2)));}
    @Test void exactSourceMetricsRetainCustomerAndDistinctPnlScopes(){
        var actual=CodingCustomerAccountVerifier.calculate(rows(),CUSTOMER,versions(),"hash");
        assertThat(actual).containsEntry("customerNumber",CUSTOMER).containsEntry("transactionGrossAmount","0.3")
            .containsEntry("transactionQuantity","2").containsEntry("transactionRows",2)
            .containsEntry("accountDayPnl","0.2").containsEntry("positionDayPnl","0.1")
            .containsEntry("positionCumulativeRealizedPnl","-0.2")
            .containsEntry("transactionMetricBasis","ORDER_EXECUTED_QUANTITY_TIMES_PRICE_EXCLUDING_FEES");
    }
    @Test void identityDatesMissingFieldsAndAmbiguousSourcesCannotPass(){
        assertThatThrownBy(()->CodingCustomerAccountVerifier.calculate(rows(),"7",versions(),"hash")).isInstanceOf(IllegalArgumentException.class);
        var changed=rows();((Map<String,Object>)changed.get(0).get("record")).put("RQ","20260801");
        assertThatThrownBy(()->CodingCustomerAccountVerifier.calculate(changed,CUSTOMER,versions(),"hash")).isInstanceOf(IllegalArgumentException.class);
        var missing=rows();((Map<String,Object>)missing.get(1).get("record")).remove("LJYK");
        assertThatThrownBy(()->CodingCustomerAccountVerifier.calculate(missing,CUSTOMER,versions(),"hash")).isInstanceOf(IllegalArgumentException.class);
        var ambiguous=versions();ambiguous.add(declaration("other-orders",order("1"),0));
        assertThatThrownBy(()->CodingCustomerAccountVerifier.calculate(rows(),CUSTOMER,ambiguous,"hash")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void actualSettlementAmountsAreNotReplacedWithOrderPriceProducts(){
        var source=new ArrayList<>(rows().subList(0,2));
        var fill=new LinkedHashMap<String,Object>(Map.of("KHH",CUSTOMER,"CJRQ",20260731,"CJJE","3.25","CJSL",2));
        source.add(row("fills",fill));var declared=versions();declared.remove(2);declared.add(declaration("fills",fill,1));
        var result=CodingCustomerAccountVerifier.calculate(source,CUSTOMER,declared,"hash");
        assertThat(result).containsEntry("transactionGrossAmount","3.25").containsEntry("transactionMetricBasis","SETTLEMENT_CJJE");
    }
    @Test void immutableByteVerifierRejectsWrongReportedValuesAndSourceTampering()throws Exception{
        var json=new ObjectMapper();var content=new StringBuilder();for(var row:rows())content.append(json.writeValueAsString(row)).append('\n');
        byte[] raw=content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        var port=mock(McpPythonControlPlanePort.class);when(port.downloadDataFile("t","u","file")).thenReturn(raw);
        var verifier=new CodingCustomerAccountVerifier(port);var scope=new KernelDataScope("t","u",null,null,"run",null,Map.of());
        var input=new CodingInputSnapshotService.Snapshot("snapshot","file","input.jsonl",hash,4,raw.length,"source");
        var rules=Map.<String,Object>of("rowSchema",Map.of("type","object","additionalProperties",true),"customerNumber",CUSTOMER,"sourceVersions",versions());
        var summary=CodingCustomerAccountVerifier.calculate(rows(),CUSTOMER,versions(),hash);
        var report=new LinkedHashMap<String,Object>(Map.of("verifiedAccountSummary",summary,"analysisStatus","COMPLETE","reportMarkdown","Customer 0007 historical account report",
            "limitations",List.of("Historical default date and single-day activity only"),"evidenceReferences",List.of("asset","holding","orders")));
        assertThat(verifier.verify(scope,input,rules,List.of(report)).passed()).isTrue();
        var wrong=new LinkedHashMap<>(summary);wrong.put("positionCumulativeRealizedPnl","999");report.put("verifiedAccountSummary",wrong);
        assertThat(verifier.verify(scope,input,rules,List.of(report)).passed()).isFalse();
        report.put("verifiedAccountSummary",summary);
        var wrongCount=new CodingInputSnapshotService.Snapshot("snapshot","file","input.jsonl",hash,5,raw.length,"source");
        assertThat(verifier.verify(scope,wrongCount,rules,List.of(report)).passed()).isFalse();
        when(port.downloadDataFile("t","u","file")).thenReturn("changed".getBytes());
        assertThatThrownBy(()->verifier.verify(scope,input,rules,List.of(report))).isInstanceOf(SecurityException.class);
    }
}
