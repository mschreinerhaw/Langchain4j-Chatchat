package com.chatchat.common.runtime.coding;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static com.chatchat.common.runtime.coding.CodingContractTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/** Validates the shipped draft-2020-12 bundle using an actual JSON Schema engine, not an imitation. */
class CodingJsonSchemaTest {
    @Test void textOnlyResultsRequireTheFrozenV3PolicyAndNoCodeExecution()throws Exception{
        var task=fixture("task");set(task,"/schemaVersion",CodingProtocol.ANALYSIS_VERSION);
        task.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT","analysisResultMode","TEXT_OR_CODE")));
        var required=(com.fasterxml.jackson.databind.node.ArrayNode)task.at("/outputContract/requiredEvidence");
        for(int i=required.size()-1;i>=0;i--)if("CODE_ARTIFACT".equals(required.get(i).asText()))required.remove(i);
        assertThat(analysisSchema("task").validate(task)).isEmpty();
        var accepted=JSON.treeToValue(task,CodingTaskContract.class);assertThat(accepted.permitsTextResult()).isTrue();
        var result=fixture("outcome");set(result,"/schemaVersion",CodingProtocol.ANALYSIS_VERSION);
        set(result,"/codeArtifactRef",null);set(result,"/usage/consumed/codeExecutions",0);
        if(result.get("perArtifactCompleteness") instanceof ObjectNode completeness)completeness.remove("CODE_ARTIFACT");
        result.set("limitations",JSON.valueToTree(List.of("Text only; no program executed")));
        set(result,"/contractSha256",CodingCanonicalHash.sha256(accepted));
        assertThat(analysisSchema("outcome").validate(result)).isEmpty();
        JSON.treeToValue(result,CodingTaskOutcome.class).validateAgainst(accepted);
        set(result,"/usage/consumed/codeExecutions",1);
        assertThat(analysisSchema("outcome").validate(result)).isNotEmpty();
        set(result,"/usage/consumed/codeExecutions",0);set(result,"/schemaVersion",CodingProtocol.VERSION);
        assertThat(analysisSchema("outcome").validate(result)).isNotEmpty();
        set(task,"/schemaVersion",CodingProtocol.VERSION);
        assertThat(analysisSchema("task").validate(task)).isNotEmpty();
    }
    private JsonSchema analysisSchema(String type)throws Exception{
        try(var input=getClass().getResourceAsStream("/runtime/coding/coding-analysis.schema.json")){
            var bundle=(ObjectNode)JSON.readTree(input);bundle.remove("oneOf");bundle.put("$ref","#/$defs/"+type);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(bundle);
        }
    }
    @Test void reportPreferencesAreAcceptedByThePublishedTaskSchemaAndJava()throws Exception{
        var node=fixture("task");
        node.set("runtimeContext",JSON.valueToTree(Map.of("schemaVersion","runtime_os.coding.context.v1","consistency","TASK_SNAPSHOT",
            "reportMode","visual","reportPresentation",Map.of("strategy","auto","density","compact","autoCharts",true,"autoImages",false))));
        assertThat(schema("task").validate(node)).isEmpty();
        assertThat(JSON.treeToValue(node,CodingTaskContract.class).runtimeContext()).containsKey("reportPresentation");
        set(node,"/runtimeContext/reportPresentation/strategy","execute-html");
        assertThat(schema("task").validate(node)).isNotEmpty();
    }
    private JsonSchema schema() throws Exception { return schema(null); }

    private JsonSchema schema(String expectedType) throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (var input = getClass().getResourceAsStream("/runtime/coding/coding.schema.json")) {
            ObjectNode bundle = (ObjectNode) JSON.readTree(input);
            if (expectedType != null) {
                bundle.remove("oneOf"); bundle.put("$ref", "#/$defs/" + expectedType);
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(bundle, config);
        }
    }

    @Test void allGoldenFixturesAndJavaSerializedRecordsValidate() throws Exception {
        JsonSchema validator = schema();
        for (var entry : Map.of("task", CodingTaskContract.class, "outcome", CodingTaskOutcome.class,
            "event", CodingExecutionEvent.class, "error", CodingAgentSPI.Error.class).entrySet()) {
            ObjectNode node = fixture(entry.getKey());
            assertThat(validator.validate(node)).as(entry.getKey()).isEmpty();
            assertThat(validator.validate(tree(JSON.treeToValue(node, entry.getValue())))).as(entry.getKey()+" serialized").isEmpty();
        }
        for (CodingTaskOutcome.Status state : CodingTaskOutcome.Status.values())
            assertThat(validator.validate(tree(outcome(state, 1)))).as(state.name()).isEmpty();
    }

    @Test void endpointsUseTheExpectedSchemaRootRatherThanAnyValidMessage() throws Exception {
        for (String type : List.of("task", "outcome", "event", "error")) {
            JsonSchema validator = schema(type);
            assertThat(validator.validate(fixture(type))).isEmpty();
            assertThat(validator.validate(fixture(type.equals("task") ? "error" : "task"))).isNotEmpty();
        }
    }

    record Invalid(String fixture, String pointer, Object value) { }
    @Test void workspaceSchemaIsVersionedAndDoesNotExpandTheV1WireProfile() throws Exception {
        try(var input=getClass().getResourceAsStream("/runtime/coding/coding-workspace.schema.json")){
            var validator=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(input));
            ObjectNode task=fixture("task");task.put("schemaVersion",CodingProtocol.WORKSPACE_VERSION);
            set(task,"/outputContract/resultFormat","WORKSPACE_FILES");
            assertThat(validator.validate(task)).isEmpty();
            assertThat(schema("task").validate(task)).isNotEmpty();
            set(task,"/outputContract/resultFormat","JSON_LINES");
            assertThat(validator.validate(task)).isNotEmpty();
        }
    }

    @TestFactory Stream<DynamicTest> invalidWireValuesAreRejectedBySchemaAndJava() {
        return Stream.of(
            new Invalid("task", "/schemaVersion", "runtime_os.coding.v2"),
            new Invalid("task", "/schemaVersion", null),
            new Invalid("task", "/scope/tenantId", ""),
            new Invalid("task", "/objective/instruction", "   "),
            new Invalid("task", "/objective/deliverables", List.of()),
            new Invalid("task", "/metadataRefs", List.of("metadata:dup", "metadata:dup")),
            new Invalid("task", "/authorization/networkPolicy", "ALL"),
            new Invalid("task", "/authorization/networkAllowlist", List.of("https://example.com")),
            new Invalid("task", "/authorization/allowedSideEffects", List.of("REPORT_PUBLICATION")),
            new Invalid("task", "/budget/maxModelCalls", -1),
            new Invalid("task", "/budget/maxCpuMillis", 9007199254740992L),
            new Invalid("task", "/budget/maxCostAmount", "1.00"),
            new Invalid("task", "/budget/maxCostAmount", "1e3"),
            new Invalid("task", "/budget/deadlineAt", "2026-10-15T12:00:00.123456Z"),
            new Invalid("task", "/budget/costCurrency", "usd"),
            new Invalid("task", "/outputContract/requiredFiles", List.of("../result.jsonl")),
            new Invalid("task", "/outputContract/resultFormat", "PICKLE"),
            new Invalid("outcome", "/status", "CLINE_DONE"),
            new Invalid("outcome", "/resultDatasetRef", null),
            new Invalid("outcome", "/contractSha256", "not-a-hash"),
            new Invalid("outcome", "/validationStatus", "NOT_RUN"),
            new Invalid("outcome", "/executionControl/executorStopped", false),
            new Invalid("outcome", "/executionControl/reconciliationRequired", true),
            new Invalid("outcome", "/reproducibility/dependencyRefs", List.of()),
            new Invalid("outcome", "/perArtifactCompleteness/RESULT_DATASET", "PARTIAL"),
            new Invalid("event", "/schemaVersion", "v0"),
            new Invalid("event", "/sequence", 0),
            new Invalid("event", "/status", "RUNNING"),
            new Invalid("event", "/type", "CLINE_MESSAGE"),
            new Invalid("event", "/operationId", "operation:without-attempt"),
            new Invalid("error", "/code", "CLINE_ERROR"),
            new Invalid("error", "/message", "")
        ).map(test -> DynamicTest.dynamicTest(test.fixture+test.pointer+"="+test.value, () -> {
            ObjectNode node = fixture(test.fixture); set(node, test.pointer, test.value);
            assertThat(schema().validate(node)).isNotEmpty();
            Class<?> type = switch (test.fixture) {
                case "task" -> CodingTaskContract.class; case "outcome" -> CodingTaskOutcome.class;
                case "event" -> CodingExecutionEvent.class; default -> CodingAgentSPI.Error.class;
            };
            assertThatThrownBy(() -> JSON.treeToValue(node, type)).isInstanceOf(Exception.class);
        }));
    }

    @Test void missingPrimitiveFieldsAndCoercibleNumbersMustFailBeforeDeserialization() throws Exception {
        JsonSchema validator = schema();
        ObjectNode missing = fixture("task"); ((ObjectNode) missing.get("budget")).remove("maxModelCalls");
        assertThat(validator.validate(missing)).isNotEmpty();
        ObjectNode stringNumber = fixture("task"); set(stringNumber, "/budget/maxModelCalls", "0");
        assertThat(validator.validate(stringNumber)).isNotEmpty();
        ObjectNode floating = fixture("task"); set(floating, "/budget/maxToolCalls", 1.5);
        assertThat(validator.validate(floating)).isNotEmpty();
        // Jackson's Instant parser normalizes offsets; the wire schema must reject them first.
        ObjectNode offset = fixture("task"); set(offset, "/budget/deadlineAt", "2026-10-15T20:00:00+08:00");
        assertThat(validator.validate(offset)).isNotEmpty();
    }

    @Test void committedEventsNeedAnOperationAndEvidenceWhileOptionalExtensionsRemainCompatible() throws Exception {
        ObjectNode event = fixture("event"); set(event, "/type", "EXECUTION_FINISHED");
        set(event, "/status", "RUNNING");
        assertThat(schema().validate(event)).isNotEmpty();
        set(event, "/operationId", "operation:001"); set(event, "/attempt", 1);
        assertThat(schema().validate(event)).isEmpty();
        set(event, "/evidenceRefs", List.of());
        assertThat(schema().validate(event)).isNotEmpty();
        ObjectNode task = fixture("task"); task.put("optionalFutureField", "accepted");
        assertThat(schema().validate(task)).isEmpty();
    }

    @Test void unicodeLengthAndWhitespaceRulesAgreeWithTheWireSchema() throws Exception {
        ObjectNode task = fixture("task");
        set(task, "/workspaceRef", "🧮".repeat(512));
        assertThat(schema().validate(task)).isEmpty();
        assertThat(JSON.treeToValue(task, CodingTaskContract.class).workspaceRef().codePointCount(0, 1024)).isEqualTo(512);
        set(task, "/workspaceRef", "🧮".repeat(513));
        assertThat(schema().validate(task)).isNotEmpty();
        assertThatThrownBy(() -> JSON.treeToValue(task, CodingTaskContract.class)).isInstanceOf(Exception.class);
        set(task, "/workspaceRef", "\u2003\u3000");
        assertThat(schema().validate(task)).isNotEmpty();
        assertThatThrownBy(() -> JSON.treeToValue(task, CodingTaskContract.class)).isInstanceOf(Exception.class);
        set(task, "/workspaceRef", "\ud800");
        assertThatThrownBy(() -> JSON.treeToValue(task, CodingTaskContract.class)).isInstanceOf(Exception.class);
    }
}
