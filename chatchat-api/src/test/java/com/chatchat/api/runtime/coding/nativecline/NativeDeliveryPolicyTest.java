package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.common.runtime.coding.CodingRuntimeContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeDeliveryPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void validatesDeclaredTypesAndNamesIndependentlyOfLegacySelection() {
        var rules=json.valueToTree(NativeDeliveryPolicy.freeze(List.of("REPORT","RESULT","TEST"),Map.of()));
        var artifacts=List.of(file("output/report/overview.MD","REPORT"),file("output/result/data.json","RESULT"),
            file("output/test/run-check.txt","TEST"));
        assertTrue(NativeDeliveryPolicy.accepts(rules,json.valueToTree(artifacts)));
        assertFalse(NativeDeliveryPolicy.accepts(rules,json.valueToTree(artifacts.subList(0,2))));
        assertFalse(NativeDeliveryPolicy.accepts(rules,json.valueToTree(List.of(
            artifacts.get(0),artifacts.get(1),file("output/report/run-check.txt","TEST")))));
        assertFalse(NativeDeliveryPolicy.accepts(rules,json.valueToTree(List.of(
            file("output/report/overview.html","REPORT"),artifacts.get(1),artifacts.get(2)))));
        assertThrows(IllegalArgumentException.class,()->NativeDeliveryPolicy.freeze(List.of("TEST","TEST"),Map.of()));
    }

    @Test void explicitTaskKindIsIndependentFromMetadataPath() {
        var path="input/data/0/data-supply-manifest.json";
        assertTrue(CodingRuntimeContext.isNativeAnalysis(Map.of("analysisMetadataPath",path)));
        assertFalse(CodingRuntimeContext.isNativeAnalysis(Map.of("nativeTaskKind","GENERIC","analysisMetadataPath",path)));
        assertTrue(CodingRuntimeContext.isNativeAnalysis(Map.of("nativeTaskKind","DATA_ANALYSIS")));
    }

    @Test void fileOnlyAnalysisCanFreezeTheResolvedReportLanguage() {
        var context=new HashMap<String,Object>();
        context.put("schemaVersion","runtime_os.coding.context.v1");
        context.put("consistency","TASK_SNAPSHOT");
        context.put(CodingRuntimeContext.NATIVE_TASK_KIND,CodingRuntimeContext.DATA_ANALYSIS);
        context.put(CodingRuntimeContext.DELIVERY_RULES,NativeDeliveryPolicy.freeze(List.of("REPORT"),context));
        String language=NativeReportLanguage.resolve("\u5206\u6790 CSV",Map.of(),Map.of());
        context.put("analysisReportLanguage",language);
        assertEquals("zh",CodingRuntimeContext.validate(context).get("analysisReportLanguage"));
        assertEquals("Chinese (zh)",NativeReportLanguage.instruction(language));
    }

    private static Map<String,String> file(String path,String type) {
        return Map.of("path",path,"type",type);
    }
}
