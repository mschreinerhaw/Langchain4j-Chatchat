package com.chatchat.e2e;

import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationCase;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationReport;
import com.chatchat.agents.runtime.evaluation.AgentEvaluationService;
import com.chatchat.agents.runtime.evaluation.AgentQualityGateReport;
import com.chatchat.agents.runtime.evaluation.AgentQualityGateService;
import com.chatchat.agents.runtime.evaluation.AgentQualityGateThresholds;
import com.chatchat.agents.runtime.trace.AgentRunTrace;
import com.chatchat.agents.runtime.trace.AnswerTrace;
import com.chatchat.agents.runtime.trace.EvidenceTrace;
import com.chatchat.agents.runtime.trace.GroundingTrace;
import com.chatchat.agents.runtime.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Mandatory, environment-independent release gate for the unified evaluation contract. */
class ProductionAgentQualityEvaluationGateE2E {

    @Test
    void offlineGoldSuiteMeetsReleaseQualityThresholds() {
        AgentEvaluationService evaluator = new AgentEvaluationService();
        AgentEvaluationCase.Thresholds caseThresholds = new AgentEvaluationCase.Thresholds(
            property("chatchat.e2e.quality.min-retrieval", 0.90D),
            property("chatchat.e2e.quality.min-tool-selection", 0.95D),
            property("chatchat.e2e.quality.min-parameter-accuracy", 0.95D),
            property("chatchat.e2e.quality.min-evidence-completeness", 0.95D),
            property("chatchat.e2e.quality.min-overall", 0.95D));
        List<AgentEvaluationReport> reports = List.of(
            evaluator.evaluate(trace("quality-document", "document_search",
                    Map.of("query", "restart configuration"), "doc://ops#3",
                    "Restart after the configuration update."),
                gold("document_search", Map.of("query", "restart configuration"),
                    "doc://ops#3", "configuration", caseThresholds)),
            evaluator.evaluate(trace("quality-financial", "mcp_chatchat_financial_data_search",
                    Map.of("query", "A股主要指数 2026年8月14日 行情数据 成交量", "limit", 10),
                    "financial://market_quote_daily/2026-08-14", "A股主要指数成交量行情。"),
                gold("financial_data_search",
                    Map.of("query", "A股主要指数 2026年8月14日 行情数据 成交量", "limit", 10),
                    "financial://market_quote_daily/2026-08-14", "成交量", caseThresholds))
        );
        AgentQualityGateThresholds thresholds = new AgentQualityGateThresholds(
            property("chatchat.e2e.quality.min-case-pass-rate", 1.0D),
            property("chatchat.e2e.quality.min-retrieval", 0.90D),
            property("chatchat.e2e.quality.min-tool-selection", 0.95D),
            property("chatchat.e2e.quality.min-parameter-accuracy", 0.95D),
            property("chatchat.e2e.quality.min-evidence-completeness", 0.95D));

        AgentQualityGateReport gate = new AgentQualityGateService().evaluate(reports, thresholds);

        assertThat(gate.passed()).as("quality gate failures: %s", gate.failures()).isTrue();
        assertThat(gate.totalCases()).isEqualTo(2);
        assertThat(gate.dimensionAverages().keySet()).containsExactlyInAnyOrder(
            "retrieval", "toolSelection", "parameterAccuracy", "evidenceCompleteness");
    }

    private AgentEvaluationCase gold(String tool, Map<String, Object> arguments, String refId, String term,
                                     AgentEvaluationCase.Thresholds thresholds) {
        return new AgentEvaluationCase("quality gate", List.of(refId), List.of(term), true,
            List.of(new AgentEvaluationCase.RetrievalExpectation(refId, List.of(term), 1)),
            List.of(new AgentEvaluationCase.ToolExpectation(tool, arguments)),
            thresholds);
    }

    private AgentRunTrace trace(String runId, String toolName, Map<String, Object> input,
                                String refId, String content) {
        Map<String, Object> citation = Map.of("refId", refId);
        ToolCallTrace tool = new ToolCallTrace(1, toolName, toolName, true, input, content, null,
            10L, 1L, 11L, "mcp-" + runId, "evidence-" + runId, Map.of(), Map.of());
        EvidenceTrace evidence = new EvidenceTrace(refId, "DOCUMENT", "release-gold", toolName,
            true, "ALLOW", content, Map.of());
        return new AgentRunTrace(AgentRunTrace.CONTRACT_VERSION, "trace-" + runId, null, runId,
            "request-" + runId, null, "release", "release", "release-agent", "release-model", null,
            "quality gate", AgentRunStatus.COMPLETED, 1L, 11L, 10L, Map.of(), List.of(tool),
            List.of(evidence), new AnswerTrace(content + " [" + refId + "]", "evidence_answer_v1",
            List.of(citation), "high", List.of()),
            new GroundingTrace("grounded", List.of(citation), List.of(citation), List.of(), List.of()),
            List.of(), List.of());
    }

    private double property(String name, double fallback) {
        return Double.parseDouble(System.getProperty(name, String.valueOf(fallback)));
    }
}
