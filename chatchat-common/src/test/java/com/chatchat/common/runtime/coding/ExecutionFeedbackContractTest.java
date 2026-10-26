package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionFeedbackContractTest {
    final ObjectMapper json=new ObjectMapper();
    ExecutionFeedback feedback(){return new ExecutionFeedback(ExecutionFeedback.VERSION,"task","operation","attempt","trace",
        "FAILED","EXECUTION_FAILED",1,25L,12L,1048576L,"a".repeat(64),"[raw stdout omitted]","safe diagnostic",0L,100L,
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
    @Test void legacyFeedbackWithoutResourceMeasurementsRemainsReadable() throws Exception {
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(feedback());
        value.remove(List.of("cpuTimeMs","maxRssBytes"));
        var legacy=json.treeToValue(value,ExecutionFeedback.class);
        assertThat(legacy.cpuTimeMs()).isNull();
        assertThat(legacy.maxRssBytes()).isNull();
    }
    @Test void legacyConstructorRetainsTheDeployedBinaryContract() throws Exception {
        var f=feedback();
        var legacy=new ExecutionFeedback(f.schemaVersion(),f.taskId(),f.executionId(),f.attemptId(),f.traceId(),
            f.status(),f.stage(),f.exitCode(),f.durationMs(),f.codeHash(),f.stdout(),f.stderr(),f.stdoutBytes(),f.stderrBytes(),
            f.error(),f.inputDataRefs(),f.schemaRef(),f.inputFiles(),f.inputSchemaSummary(),f.artifactRefs(),f.validationStatus(),
            f.resultAvailable(),f.manifestHash(),f.outputStatus(),f.businessValidationPassed(),f.repair(),f.diagnosticPolicy(),f.script());
        var expected=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(f);
        expected.remove(List.of("cpuTimeMs","maxRssBytes"));
        assertThat(legacy).isEqualTo(json.treeToValue(expected,ExecutionFeedback.class));
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
    @Test void newScriptAllocationNeverUsesChineseOrOtherNonAsciiCharacters() throws Exception {
        String fixture="""
            {"schemaVersion":"runtime_os.coding.v1","taskId":"ascii-task","scope":{"tenantId":"tenant","userId":"owner","runId":"run"},
             "objective":{"instruction":"销售汇总分析","deliverables":["rows.jsonl"],"acceptanceCriteriaRefs":["criteria"]},
             "inputSnapshotRef":"input","metadataRefs":[],"workspaceRef":"workspace",
             "authorization":{"decisionRef":"decision","allowedTools":[],"allowedDataRefs":[],"readablePathRefs":[],"writablePathRefs":[],"networkPolicy":"NONE","networkAllowlist":[],"allowedSideEffects":[]},
             "budget":{"budgetId":"budget","deadlineAt":"2026-10-23T00:00:00Z","maxModelCalls":1,"maxModelTokens":100,"maxToolCalls":1,"maxCodeExecutions":1,"maxCostAmount":"1","costCurrency":"USD","maxCpuMillis":1,"maxMemoryBytes":1,"maxOutputBytes":1},
             "environmentRef":"environment","validationPlanRef":"plan",
             "outputContract":{"resultFormat":"JSON_LINES","resultSchemaRef":"schema","validationSchemaRef":"validation","requiredFiles":["rows.jsonl"],"acceptedMediaTypes":["application/json"],"maxResultRows":1,"maxResultBytes":1,"requiredEvidence":[]}}
            """;
        var mapper=new ObjectMapper().findAndRegisterModules();
        var at=java.time.Instant.parse("2026-10-22T12:00:00Z");
        for(String purpose:List.of("销售汇总分析","销售 Python 数据分析 CSV","Sales Summary","日本語分析","résumé 数据")){
            var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(fixture);
            ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("objective")).put("instruction",purpose);
            var script=CodingScript.allocate(mapper.treeToValue(value,CodingTaskContract.class),at,1);
            assertThat(script.fileName()).matches("[a-z0-9-]+_20261022T120000000Z_[0-9a-f]{12}_v1\\.py");
            assertThat(script.fileName().chars().allMatch(c->c<128)).isTrue();
            if(purpose.equals("销售汇总分析"))assertThat(script.fileName()).startsWith("analysis_");
        }
    }
}
