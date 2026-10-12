package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HarnessToolAccessTest {
    @Test void workflowToolsAreNeitherAdvertisedNorExecutedByAnalysisHarness() {
        var invocations = new AtomicInteger();
        for (var role : List.of(com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY,
                com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY,
                com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_EXECUTION)) {
            when(registry.getToolMetadata("arbitrary_name")).thenReturn(ToolMetadata.builder()
                .agentCompatible(true).operationType("read").metadata(Map.of("workflowContract",
                    com.chatchat.common.tool.ToolWorkflowContract.declaration(role, "configured", "typed"))).build());
            var access = new HarnessToolAccess(registry, List.of("arbitrary_name"), (name,args) -> {
                invocations.incrementAndGet(); return null;
            }, evidence, Map.of(), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
            assertThat(access.capabilities()).doesNotContain("arbitrary_name");
            assertThatThrownBy(() -> access.call(Map.of("toolName", "arbitrary_name", "arguments", Map.of()),
                new LinkedHashMap<>())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not authorized");
        }
        assertThat(invocations).hasValue(0);
    }

    @Test void workflowRoleChangesRevokePreviouslyAdvertisedCapability() {
        when(registry.getToolMetadata("reader")).thenReturn(ToolMetadata.builder()
            .agentCompatible(true).operationType("read").metadata(Map.of("inputSchema", Map.of("type", "object"))).build());
        var access = new HarnessToolAccess(registry, List.of("reader"), (name,args) -> {
            throw new AssertionError("Revoked tool must not execute");
        }, evidence, Map.of(), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
        assertThat(access.capabilities()).contains("reader");
        when(registry.getToolMetadata("reader")).thenReturn(ToolMetadata.builder()
            .agentCompatible(true).operationType("read").metadata(Map.of("workflowContract",
                com.chatchat.common.tool.ToolWorkflowContract.declaration(
                    com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY, "configured", "typed"))).build());
        assertThat(access.capabilities()).doesNotContain("reader");
        assertThatThrownBy(() -> access.call(Map.of("toolName", "reader", "arguments", Map.of()),
            new LinkedHashMap<>())).isInstanceOf(IllegalArgumentException.class);
    }
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final AnalysisEvidenceCoordinator evidence = mock(AnalysisEvidenceCoordinator.class);
    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation");
    private final Map<String,Object> metadata = new LinkedHashMap<>();

    @Test void taskMarkerCannotOpenExecutionToolsInAnalysisContinuation() {
        when(registry.getToolMetadata("registered_executor")).thenReturn(ToolMetadata.builder()
            .agentCompatible(true).operationType("execute").build());
        var invocations = new java.util.concurrent.atomic.AtomicInteger();
        var access = new HarnessToolAccess(registry, List.of("registered_executor"), (name,args) -> {
            invocations.incrementAndGet();
            return new ToolRuntimeExecution(ToolOutput.success(Map.of()), null, null, "success", Map.of());
        }, evidence, Map.of("modelNativeTask", true), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> access.call(
            Map.of("toolName", "registered_executor", "arguments", Map.of()), new LinkedHashMap<>()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not authorized");
        assertThat(invocations).hasValue(0);
    }

    @Test void acquiredEvidenceStaysInContextAfterAnInvalidModelResponse() {
        when(registry.getToolMetadata("discovery")).thenReturn(ToolMetadata.builder()
            .agentCompatible(true).operationType("read").build());
        when(evidence.project(any(), anyMap())).thenReturn(new AnalysisEvidenceCoordinator.Projection(List.of(), List.of()));
        var access = new HarnessToolAccess(registry, List.of("discovery"), (name,args) ->
            new ToolRuntimeExecution(ToolOutput.success(Map.of("asset", "authorized-asset-A")), null, null, "success", Map.of()),
            evidence, Map.of(), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
        var model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn(
            "{\"schemaVersion\":\"model_native_analysis.v2\",\"decision\":{\"action\":\"CONTINUE\"},"
                + "\"evidenceRequests\":[{\"operation\":\"CALL_TOOL\",\"toolName\":\"discovery\",\"arguments\":{}}]}",
            "{\"schemaVersion\":null}",
            "{\"schemaVersion\":\"model_native_analysis.v2\",\"decision\":{\"action\":\"COMPLETE\"},\"output_type\":\"final\",\"content\":\"Report\"}");
        new ModelNativeAnalysisHarness(4).withToolAccess(access).execute("Discover", List.of(), model,
            scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {});
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model, times(3)).chat(prompts.capture());
        assertThat(prompts.getAllValues().get(2)).contains("INVALID_TURN_PROTOCOL", "authorized-asset-A", "datasetReference");
        assertThat(metadata).containsEntry("harnessToolCalls", 1);
    }

    @Test void modelMayContinueWithAuthorizedToolAndReceiveQueryableResultHandles() {
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().agentCompatible(true).operationType("read")
            .metadata(Map.of("inputSchema", Map.of("type", "object"))).build());
        when(evidence.project(any(), anyMap())).thenReturn(new AnalysisEvidenceCoordinator.Projection(
            List.of(new AnalysisEvidenceCoordinator.Dataset("provider_result", Map.of(), List.of(Map.of("value", 7)))), List.of()));
        AtomicInteger calls = new AtomicInteger();
        var access = new HarnessToolAccess(registry, List.of("read_tool"), (name,args) -> {
            calls.incrementAndGet(); assertThat(args).containsEntry("dataset", "registered");
            return new ToolRuntimeExecution(ToolOutput.success(Map.of("rows", List.of(Map.of("value", 7)))), null, null, "success", Map.of());
        }, evidence, Map.of(), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"schemaVersion\":\"model_native_analysis.v1\",\"completed\":false,\"evidenceRequests\":[{\"operation\":\"CALL_TOOL\",\"toolName\":\"read_tool\",\"arguments\":{\"dataset\":\"registered\"}}]}",
            "{\"schemaVersion\":\"model_native_analysis.v1\",\"completed\":true,\"reportMarkdown\":\"Model authored analysis\"}");
        var result = new ModelNativeAnalysisHarness(3).withToolAccess(access).execute("analyze",
            List.of(new AnalysisEvidenceCoordinator.Dataset("initial", Map.of(), List.of(Map.of("seed", 1)))), model,
            scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {});
        assertThat(calls).hasValue(1);
        assertThat(result.markdown()).isEqualTo("Model authored analysis");
        assertThat(result.datasetReferences()).contains("initial", "harness:tool:1:1");
        assertThat(metadata).containsEntry("harnessToolCalls", 1);
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(model, times(2)).chat(prompts.capture());
        assertThat(prompts.getAllValues().get(1)).contains("harness:tool:1:1", "CALL_TOOL", "read_tool");
    }

    @Test void unauthorizedWritesAndBudgetOverflowNeverInvokeExecution() {
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().agentCompatible(true).operationType("read").build());
        when(registry.getToolMetadata("write_tool")).thenReturn(ToolMetadata.builder().agentCompatible(true).operationType("write").build());
        AtomicInteger calls = new AtomicInteger();
        var access = new HarnessToolAccess(registry, List.of("read_tool", "write_tool"), (name,args) -> {
            calls.incrementAndGet(); throw new AssertionError("No execution permitted");
        }, evidence, Map.of("mcpWorkflow", Map.of("executionStrategy", Map.of("maxSteps", 2))), metadata,
            scope, AnalysisEvidenceSpillStore.disabled(), 2);
        assertThatThrownBy(() -> access.call(Map.of("toolName", "unregistered", "arguments", Map.of()), new LinkedHashMap<>()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not authorized");
        assertThatThrownBy(() -> access.call(Map.of("toolName", "write_tool", "arguments", Map.of()), new LinkedHashMap<>()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not authorized");
        assertThatThrownBy(() -> access.call(Map.of("toolName", "read_tool", "arguments", Map.of()), new LinkedHashMap<>()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget exhausted");
        assertThat(calls).hasValue(0);
    }

    @Test void v2ModelChoosesSameToolWithDifferentParametersAndCanReadOldEvidence() {
        when(registry.getToolMetadata("read_tool")).thenReturn(ToolMetadata.builder().agentCompatible(true).operationType("read").build());
        when(evidence.project(any(), anyMap())).thenReturn(new AnalysisEvidenceCoordinator.Projection(
            List.of(new AnalysisEvidenceCoordinator.Dataset("result", Map.of(), List.of(Map.of("value", 7)))), List.of()));
        var arguments = new ArrayList<Map<String,Object>>();
        var access = new HarnessToolAccess(registry, List.of("read_tool"), (name,args) -> {
            arguments.add(args); return new ToolRuntimeExecution(ToolOutput.success(Map.of("value", 7)), null, null, "success", Map.of());
        }, evidence, Map.of(), metadata, scope, AnalysisEvidenceSpillStore.disabled(), 0);
        var model = mock(ChatModel.class);
        var turns = new ArrayList<String>();
        for (int value : List.of(1,2)) turns.add(com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of(
            "schemaVersion", "model_native_analysis.v2", "decision", Map.of("action", "CONTINUE"),
            "evidenceRequests", List.of(Map.of("operation", "CALL_TOOL", "toolName", "read_tool", "arguments", Map.of("sample", value)),
                Map.of("operation", "READ_RECORDS", "datasetReference", "initial", "fromRecord", 1, "limit", 1)))));
        turns.add(com.chatchat.agents.protocol.ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v2",
            "decision", Map.of("action", "PUBLISH"), "reportMarkdown", "Model revised interpretation")));
        when(model.chat(anyString())).thenReturn(turns.get(0), turns.get(1), turns.get(2));
        new ModelNativeAnalysisHarness(4).withToolAccess(access).execute("analyze", List.of(
            new AnalysisEvidenceCoordinator.Dataset("initial", Map.of(), List.of(Map.of("seed", 1)))), model,
            scope, AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, event -> {});
        assertThat(arguments).containsExactly(Map.of("sample",1), Map.of("sample",2));
        assertThat(metadata).containsEntry("publicationState", "REQUESTED");
        assertThat(((Map<?,?>)metadata.get("modelDecision")).get("action")).isEqualTo("PUBLISH");
    }

}
