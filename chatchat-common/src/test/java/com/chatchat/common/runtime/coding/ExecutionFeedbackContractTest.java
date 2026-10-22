package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionFeedbackContractTest {
    final ObjectMapper json=new ObjectMapper();
    ExecutionFeedback feedback(){return new ExecutionFeedback(ExecutionFeedback.VERSION,"task","operation","attempt","trace",
        "FAILED","EXECUTION_FAILED",1,25L,"a".repeat(64),"[raw stdout omitted]","safe diagnostic",0L,100L,
        new ExecutionFeedback.Failure("SYNTAX","SyntaxError","SYNTAX_ERROR",ExecutionFailureClassifier.guidance("SYNTAX_ERROR"),
            List.of(new ExecutionFeedback.Frame("main.py",4,null)),"execution:operation:diagnostic"),
        List.of("input"),"schema",List.of(),Map.of(),Map.of("diagnosticRef","diagnostic"),"NOT_RUN",false,"b".repeat(64),null,false,
        new ExecutionFeedback.Repair(true,"REPAIR_IN_CURRENT_CONTEXT","TECHNICAL_REPAIR",0,3,"a".repeat(64),"Repair syntax"),"METADATA_ONLY_V1",null);}
    @Test void javaWireRoundTripMatchesTheSharedSchemaAndRemainsImmutable() throws Exception {
        var feedback=feedback();
        assertThat(json.readValue(json.writeValueAsBytes(feedback),ExecutionFeedback.class)).isEqualTo(feedback);
        try(var source=getClass().getResourceAsStream("/runtime/coding/execution-feedback.schema.json")){
            var schema=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(json.readTree(source));
            assertThat(schema.validate(json.valueToTree(feedback))).isEmpty();
            var invalid=json.valueToTree(feedback);((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("stdout","private customer data");
            assertThat(schema.validate(invalid)).isNotEmpty();
        }
        assertThatThrownBy(()->feedback.artifactRefs().put("raw","secret")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->feedback.error().stack().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void permissionsResourcesAndUnknownErrorsCannotBeRepaired(){
        for(String code:List.of("PERMISSION_DENIED","RESOURCE_EXHAUSTED","UNKNOWN","TIMEOUT","CANCELLED"))
            assertThat(ExecutionFailureClassifier.repairable(code)).isFalse();
        assertThat(ExecutionFailureClassifier.repairable("PACKAGE_NOT_INSTALLED")).isTrue();
        assertThat(ExecutionFailureClassifier.guidance("PACKAGE_NOT_INSTALLED")).contains("prohibited");
        assertThat(ExecutionFailureClassifier.category(ExecutionFailureClassifier.code("TimeoutError"))).isEqualTo("TIMEOUT");
        assertThat(ExecutionFailureClassifier.category(ExecutionFailureClassifier.code("FileNotFoundError"))).isEqualTo("INPUT_REFERENCE");
    }
    @Test void dynamicUnicodeScriptIdentityAndFramesRoundTripAcrossTheSharedSchema() throws Exception {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(feedback());
        String file="销售汇总_20261022T080000000Z_abcdef012345_v2.py";
        var script=new CodingScript(file,"/workspace/scripts/"+file,"tasks/"+"a".repeat(16)+"/"+"b".repeat(16)+"/"+"c".repeat(16)+"/"+"d".repeat(24),2);
        value.set("script",json.valueToTree(script));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("error").path("stack").get(0)).put("file",file);
        assertThat(json.treeToValue(value,ExecutionFeedback.class).script()).isEqualTo(script);
        try(var source=getClass().getResourceAsStream("/runtime/coding/execution-feedback.schema.json")){
            assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(json.readTree(source)).validate(value)).isEmpty();
        }
        assertThatThrownBy(()->new CodingScript(file,"/tmp/"+file,script.workingDirectory(),2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CodingScript.safeFileName("../customer.py")).isFalse();
    }
}
