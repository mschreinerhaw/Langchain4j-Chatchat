package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;

import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.analysis.RocksDbAnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Release E2E: Linux, SQL, JMX and API result sets spill losslessly and resume their loop summaries
 * after a simulated JVM restart without invoking the model again.
 */
class ProductionAnalysisSpillCheckpointE2E {

    @TempDir
    Path tempDir;

    @Test
    void productionSpringContainerConstructsAndInitializesSpillBean() {
        AgentRuntimeProperties properties = properties();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentRuntimeProperties.class, () -> properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RocksDbAnalysisEvidenceSpillStore.class);
            context.refresh();

            assertThat(context.getBean(RocksDbAnalysisEvidenceSpillStore.class).isEnabled()).isTrue();
        }
    }

    @Test
    void allExecutionFamiliesSpillReplayAndResumeAcrossRestart() {
        AgentRuntimeProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger firstModelCalls = new AtomicInteger();
        ChatModel firstModel = mock(ChatModel.class);
        when(firstModel.chat(anyString())).thenAnswer(invocation -> {
            firstModelCalls.incrementAndGet();
            return "source-neutral governed chunk summary";
        });
        InterpretationPlanRuntime.ExecutionResult result = executionResult();
        Map<String, Object> runtime = Map.of("__agentRunId", "e2e-spill-run");

        RocksDbAnalysisEvidenceSpillStore firstStore =
            new RocksDbAnalysisEvidenceSpillStore(properties, objectMapper);
        firstStore.open();
        ToolRuntimeService firstRuntime = runtimeService();
        AgentOrchestrator firstOrchestrator = orchestrator(firstModel, firstRuntime);
        firstOrchestrator.setAnalysisEvidenceSpillStore(firstStore);
        Map<String, Object> firstMetadata = metadata();
        AgentOrchestrator.RecordCoverageBundle first;
        try {
            first = firstOrchestrator.buildRecordCoverageBundle(
                firstModel, "analyze every returned execution result", result,
                runtime, firstMetadata, () -> false);
        } finally {
            firstRuntime.shutdown();
            firstStore.close();
        }

        ChatModel failIfCalled = mock(ChatModel.class);
        doAnswer(invocation -> {
            throw new AssertionError("checkpoint recovery must not repeat completed model summaries");
        }).when(failIfCalled).chat(anyString());
        RocksDbAnalysisEvidenceSpillStore reopened =
            new RocksDbAnalysisEvidenceSpillStore(properties, objectMapper);
        reopened.open();
        ToolRuntimeService secondRuntime = runtimeService();
        AgentOrchestrator secondOrchestrator = orchestrator(failIfCalled, secondRuntime);
        secondOrchestrator.setAnalysisEvidenceSpillStore(reopened);
        Map<String, Object> secondMetadata = metadata();
        AgentOrchestrator.RecordCoverageBundle restored;
        try {
            restored = secondOrchestrator.buildRecordCoverageBundle(
                failIfCalled, "analyze every returned execution result", result,
                runtime, secondMetadata, () -> false);
        } finally {
            secondRuntime.shutdown();
            reopened.close();
        }

        assertThat(first.iterations()).isGreaterThanOrEqualTo(4);
        assertThat(firstModelCalls).hasValue(first.iterations());
        assertThat(first.coverageComplete()).isTrue();
        assertThat(restored.coverageComplete()).isTrue();
        assertThat(restored.processedRecordCount()).isEqualTo(first.returnedRecordCount());
        assertThat(secondMetadata)
            .containsEntry("recordAnalysisSpilledChunkCount", restored.iterations())
            .containsEntry("recordAnalysisRestoredCheckpointCount", restored.iterations())
            .containsEntry("recordAnalysisEvidenceTraceComplete", true);
        assertThat(restored.promptEvidence())
            .contains("LINUX_LAST_LINE_119", "SQL_LAST_ROW_79", "JMX_LAST_SAMPLE_79", "API_LAST_ROW_79")
            .contains("ROCKSDB_ANALYSIS_SPILL", "Raw evidence replay (lossless")
            .doesNotContain("externalized-preview", "explicitTruncation=true");
        assertThat(restored.summaryResults()).allSatisfy(summary -> {
            assertThat(summary.evidence()).containsKeys(
                "contentSha256", "rawReplayLocator", "spillCheckpointKey", "commandContext");
            assertThat(summary.evidence().get("rawReplayLocator").toString())
                .contains("analysis_evidence_spill.v1", "ROCKSDB_ANALYSIS_SPILL");
        });
    }

    private InterpretationPlanRuntime.ExecutionResult executionResult() {
        StringBuilder stdout = new StringBuilder();
        for (int index = 0; index < 120; index++) {
            stdout.append("linux-line-").append(index).append('-').append("L".repeat(180));
            if (index == 119) stdout.append("-LINUX_LAST_LINE_119");
            stdout.append('\n');
        }
        List<Map<String, Object>> sqlRows = rows("SQL", "SQL_LAST_ROW_79");
        List<Map<String, Object>> jmxRows = rows("JMX", "JMX_LAST_SAMPLE_79");
        List<Map<String, Object>> apiRows = rows("API", "API_LAST_ROW_79");

        List<ToolCallResult> children = List.of(
            child("linux", "linux_command_execute", "CHECK_PROCESS", Map.of(
                "dataSchema", "ssh_steps.v1",
                "data", Map.of("stdout", stdout.toString(), "stderr", "",
                    "outputLimits", Map.of("stdoutTruncated", false, "stderrTruncated", false)))),
            child("sql", "sql_execute", "CHECK_SQL", Map.of(
                "analysisContext", context("SQL execution"),
                "data", Map.of("rows", sqlRows))),
            child("jmx", "jmx_monitor_execute", "CHECK_JMX", Map.of(
                "structuredData", List.of(Map.of(
                    "dataset", "jvm.samples", "analysisContext", context("JMX execution"),
                    "records", jmxRows)))),
            child("api", "api_template_execute", "CHECK_API", Map.of(
                "analysisContext", context("API execution"),
                "data", Map.of("body", apiRows)))
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "all-families", "SEQUENTIAL", "start", "end", "SUCCESS",
            new ToolCallBatchResult.Summary(4, 4, 0, 0, 0, 4), children);
        return new InterpretationPlanRuntime.ExecutionResult(
            "success", true, false, null, null,
            List.of(new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "generic_execute", true, batch,
                null, null, null, 10L, Map.of("batchExecution", true))),
            Map.of(), 10L);
    }

    private List<Map<String, Object>> rows(String family, String lastMarker) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            rows.add(Map.of(
                "family", family,
                "rowId", index,
                "value", (index == 79 ? lastMarker : family + "_ROW_" + index) + "-" + "x".repeat(220)
            ));
        }
        return rows;
    }

    private ToolCallResult child(String callId, String toolName, String templateCode, Object output) {
        return new ToolCallResult(callId, toolName, templateCode, "target-1",
            "SUCCESS", 10L, "evidence-" + callId, output, Map.of());
    }

    private Map<String, Object> context(String description) {
        return Map.of(
            "source", Map.of("displayName", description),
            "extensions", Map.of("commandContext", Map.of(
                "description", description,
                "resultReferences", List.of("previous-result", "current-result")))
        );
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setAnalysisSpillEnabled(true);
        properties.setRocksDbPath(tempDir.resolve("run-store").toString());
        properties.setAnalysisSpillRocksDbPath(tempDir.resolve("analysis-spill").toString());
        properties.setAnalysisSpillThresholdBytes(1_024);
        return properties;
    }

    private Map<String, Object> metadata() {
        return new LinkedHashMap<>(Map.of(
            "tenantId", "tenant-e2e", "userId", "user-e2e",
            "agentRunId", "e2e-spill-run", "requestId", "request-e2e",
            "conversationId", "conversation-e2e"));
    }

    private ToolRuntimeService runtimeService() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> ToolMetadata.builder()
                .id(invocation.getArgument(0)).title(invocation.getArgument(0)).build());
        return new ToolRuntimeService(
            registry, new ObjectMapper(), new ToolRuntimeProperties(), List.of(), List.of());
    }

    private AgentOrchestrator orchestrator(ChatModel model, ToolRuntimeService runtime) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> ToolMetadata.builder()
                .id(invocation.getArgument(0)).title(invocation.getArgument(0)).build());
        return new AgentOrchestrator(model, registry, runtime, new ObjectMapper(), new ModelsConfig());
    }
}
