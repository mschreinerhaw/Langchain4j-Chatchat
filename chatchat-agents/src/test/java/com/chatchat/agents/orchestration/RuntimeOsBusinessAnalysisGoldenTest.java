package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.runtime.answer.DefaultAgentAnswerReviewer;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.observation.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Business-level release gates for the complete Runtime OS dataset-analysis path. */
class RuntimeOsBusinessAnalysisGoldenTest {

    @Test
    void customerProfileWaitsForAndAnalyzesEveryReturnedBusinessDataset() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String prompt) {
                return customerSummary(prompt);
            }
        };
        AgentOrchestrator orchestrator = orchestrator(model, runStore, 4);
        InterpretationPlanRuntime.ExecutionResult result = execution(List.of(
            dataset(1, "customer_assets", Map.of(
                "CUSTOMER_ID", "070200046604", "TOTAL_ASSET", 847174.25,
                "SECURITY_VALUE", 846262.20, "CASH", 912.05)),
            dataset(2, "customer_orders", Map.of(
                "CUSTOMER_ID", "070200046604", "ORDER_COUNT", 20,
                "FILLED_COUNT", 18, "CANCELLED_COUNT", 2)),
            dataset(3, "customer_positions", Map.of(
                "CUSTOMER_ID", "070200046604", "POSITION_COUNT", 20,
                "MARKET_COUNT", 2)),
            dataset(4, "customer_realized_results", Map.of(
                "CUSTOMER_ID", "070200046604", "CLOSED_POSITION_COUNT", 2,
                "MIN_HOLDING_DAYS", 1, "MAX_HOLDING_DAYS", 2))));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "customer-analysis-golden"));

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model,
            "查询客户070200046604的交易、资产与盈亏并总结交易偏好",
            result,
            Map.of("__agentRunId", "customer-analysis-golden"),
            metadata,
            () -> false);

        assertThat(coverage.returnedRecordCount()).isEqualTo(4);
        assertThat(coverage.processedRecordCount()).isEqualTo(4);
        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.summaryResults()).extracting(summary ->
            String.valueOf(summary.position().get("datasetReference")))
            .containsExactly("customer_assets", "customer_orders",
                "customer_positions", "customer_realized_results");
        assertThat(coverage.summaryResults()).allSatisfy(summary -> {
            assertThat(summary.content()).doesNotContain("没有业务数据", "未进入分析");
            assertThat(summary.evidence().get("facts")).asList().isNotEmpty();
        });
        assertThat(metadata)
            .containsEntry("recordAnalysisSummaryScheduledTaskCount", 4)
            .containsEntry("recordAnalysisReturnedRecordCount", 4)
            .containsEntry("recordAnalysisProcessedRecordCount", 4)
            .containsEntry("recordAnalysisCoverageComplete", true);

        List<AgentObservation> progress = runStore.observations("customer-analysis-golden").stream()
            .filter(observation -> "business_analysis_progress".equals(observation.source()))
            .toList();
        assertThat(progress.stream()
            .filter(observation -> "BUSINESS_RESULT_READY".equals(observation.metadata().get("stage"))))
            .hasSize(4);
        assertThat(progress).allSatisfy(observation -> {
            assertThat(observation.content()).doesNotContain("Driver", "Worker", "driver", "worker");
            assertThat(observation.metadata()).doesNotContainKeys("workerId", "taskId");
        });
    }

    @Test
    void etfFlowClaimIsRejectedAndConvertedToGapWithoutProducerSemantics() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        ChatModel model = new ChatModel() {
            @Override
            public String chat(String prompt) {
                return """
                {
              "summary":"ETF 资金净流入 120 万份。",
              "demandAnalysis":{"decisionGoal":"Analyze ETF scale and flow","answeredQuestions":[],
                "openQuestions":["Flow semantics require producer authorization"]},
              "metricAssociations":[],
              "insights":[{
                "claimClass":"AUTHORIZED_DERIVED_MEASURE",
                "operation":"DERIVE",
                "claim":"ETF 资金净流入 120 万份",
                "significance":"判断市场资金流向",
                "recordRefs":["etf_scale.records[1]"],
                "supportingValues":["1120","1000"],
                "method":"1120 - 1000",
                "inputFields":["CURRENT_SHARE","PREVIOUS_SHARE"],
                "outputUnit":"10k units",
                "grain":"fund",
                "timeScope":"two returned dates",
                "populationScope":"returned funds",
                "semanticBasis":["share change represents capital flow"],
                "confidence":"HIGH",
                "caveats":[],"alternativeExplanations":[]
              }],
              "facts":[{"claim":"返回份额为1120和1000",
                "recordRefs":["etf_scale.records[1]"],
                "exactValues":["1120","1000"]}],
              "conflicts":[],"limitations":[],"rawReplayRecommended":false
                }
                """;
            }
        };
        AgentOrchestrator orchestrator = orchestrator(model, runStore, 1);
        InterpretationPlanRuntime.ExecutionResult result = execution(List.of(
            dataset(1, "etf_scale", Map.of(
                "FUND_CODE", "512000", "CURRENT_SHARE", 1120,
                "PREVIOUS_SHARE", 1000))));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
            "agentRunId", "etf-analysis-golden"));

        AgentOrchestrator.RecordCoverageBundle coverage = orchestrator.buildRecordCoverageBundle(
            model, "分析最新ETF规模与份额，观察市场资金流向", result,
            Map.of("__agentRunId", "etf-analysis-golden"), metadata, () -> false);

        assertThat(coverage.coverageComplete()).isTrue();
        assertThat(coverage.summaryResults()).singleElement().satisfies(summary -> {
            assertThat(summary.content())
                .doesNotContain("资金净流入 120")
                .contains("未产生通过语义授权校验的分析洞察");
            assertThat(summary.evidence())
                .containsEntry("rejectedInsightCount", 1)
                .containsEntry("rawReplayRecommended", true);
            assertThat(summary.evidence().get("claimAdmissionDecisions")).asString()
                .contains("OPERATION_NOT_AUTHORIZED", "SEMANTIC_BASIS_MISMATCH", "admitted=false");
            assertThat(summary.evidence().get("semanticGapRequests")).asString()
                .contains("requiredCapabilities=[etf_scale, DERIVE]", "Resolve the rejected claim");
        });
    }

    private AgentOrchestrator orchestrator(ChatModel model, InMemoryAgentRunStore runStore,
        int workerCount) {
        ToolRegistry registry = mock(ToolRegistry.class);
        ObjectMapper mapper = new ObjectMapper();
        AgentRuntimeProperties runtime = new AgentRuntimeProperties();
        runtime.setAnalysisSummaryWorkerCount(workerCount);
        return new AgentOrchestrator(model, registry,
            new ToolRuntimeService(registry, mapper, toolRuntimeProperties(), List.of(), List.of()),
            mapper, new ModelsConfig(), new EvidenceTrustEvaluator(), runStore,
            new DefaultAgentObservationPipeline(), new DefaultAgentAnswerReviewer(mapper),
            null, runtime);
    }

    private InterpretationPlanRuntime.ExecutionResult execution(
        List<InterpretationPlanRuntime.StepExecution> steps) {
        return new InterpretationPlanRuntime.ExecutionResult(
            "completed", true, false, null, null, steps, Map.of(), 10L);
    }

    private InterpretationPlanRuntime.StepExecution dataset(int stepId, String reference,
        Map<String, Object> record) {
        List<Map<String, Object>> fields = record.keySet().stream()
            .map(name -> Map.<String, Object>of("name", name))
            .toList();
        return new InterpretationPlanRuntime.StepExecution(
            stepId, "mcp_tool", reference, true,
            Map.of("analysisContext", Map.of(
                    "source", Map.of("displayName", reference),
                    "schema", Map.of("fields", fields)),
                "data", Map.of("body", List.of(record))),
            null, null, null, 5L, Map.of());
    }

    private String customerSummary(String prompt) {
        String reference = List.of("customer_assets", "customer_orders", "customer_positions",
                "customer_realized_results").stream()
            .filter(prompt::contains).findFirst().orElse("customer_dataset");
        String value = switch (reference) {
            case "customer_assets" -> "847174.25";
            case "customer_orders" -> "20";
            case "customer_positions" -> "20";
            case "customer_realized_results" -> "2";
            default -> "1";
        };
        String report = """
            {"summary":"%s 已完成业务分析。",
             "facts":[{"claim":"%s 返回关键值 %s",
               "recordRefs":["%s.records[1]"],"exactValues":["%s"]}],
             "insights":[],"conflicts":[],"limitations":[],"rawReplayRecommended":false}
            """.formatted(reference, reference, value, reference, value);
        return report.replaceFirst("\\{", "{\"demandAnalysis\":{"
            + "\"decisionGoal\":\"Analyze the returned customer dataset\","
            + "\"answeredQuestions\":[\"The returned dataset was analyzed\"],"
            + "\"openQuestions\":[]},\"metricAssociations\":[],");
    }

    private ToolRuntimeProperties toolRuntimeProperties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setDefaultRetryAttempts(0);
        return properties;
    }
}
