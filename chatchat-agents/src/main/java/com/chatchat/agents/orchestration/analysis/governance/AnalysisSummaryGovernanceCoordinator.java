package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.protocol.RuntimeProtocolDefaults;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.DataAnalysisSummaryProtocol;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Applies the final summary governance protocol and persists its observation. */
public final class AnalysisSummaryGovernanceCoordinator {

    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;
    private DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol;

    public AnalysisSummaryGovernanceCoordinator(AgentRunResultAdapter resultAdapter,
                                                String runIdAttribute) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
        this.protocol = RuntimeProtocolDefaults.analysisSummary();
    }

    public void setProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        if (protocol != null) this.protocol = protocol;
    }

    public AnalysisSummaryResult finalizeSummary(FinalSummaryRequest request) {
        GovernanceIsolationScope scope = isolationScope(request);
        Map<String, Object> coverage = Map.of(
            "returnedRecordCount", request.returnedRecordCount(),
            "processedRecordCount", request.processedRecordCount(),
            "coverageComplete", request.coverageComplete(),
            "evidenceTraceComplete", request.evidenceTraceComplete(),
            "sourceContentComplete", request.sourceContentComplete(),
            "iterationCount", request.iterationCount(),
            "summaryResultCount", request.summaryResults().size(),
            "rawReplayChunkCount", request.rawReplayChunkCount());
        AnalysisSummaryResult result = protocol.finalResult(
            scope, request.stage(), request.content(), request.outcome(), coverage,
            request.synthesisInputs());
        if (request.metadata() != null) {
            request.metadata().put("analysisSummaryResult", result.toMap());
            request.metadata().put("analysisSummaryResultSchemaVersion", AnalysisSummaryResult.SCHEMA_VERSION);
        }
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("type", "analysis_summary_result");
        observation.put("analysisSummaryResult", result.toMap());
        observation.put("tenantId", result.isolationScope().tenantId());
        observation.put("runId", result.isolationScope().runId());
        resultAdapter.recordRuntimeObservation(
            request.runtimeAttributes(), runIdAttribute,
            "Governed final analysis summary recorded for " + result.resultId() + ".",
            "analysis_summary_governance",
            observation);
        return result;
    }

    private GovernanceIsolationScope isolationScope(FinalSummaryRequest request) {
        if (!request.summaryResults().isEmpty()) {
            return request.summaryResults().get(0).isolationScope();
        }
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
        Map<String, Object> attributes = request.runtimeAttributes() == null
            ? Map.of() : request.runtimeAttributes();
        return GovernanceIsolationScope.runtime(
            text(metadata.get("tenantId")), text(metadata.get("userId")),
            firstNonBlank(text(attributes.get(runIdAttribute)), text(metadata.get("agentRunId"))),
            text(metadata.get("requestId")), text(metadata.get("conversationId")));
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record FinalSummaryRequest(
        String stage,
        String content,
        String outcome,
        int returnedRecordCount,
        int processedRecordCount,
        boolean coverageComplete,
        boolean evidenceTraceComplete,
        boolean sourceContentComplete,
        int iterationCount,
        int rawReplayChunkCount,
        List<AnalysisSummaryResult> summaryResults,
        List<AnalysisSummaryResult> synthesisInputs,
        Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata
    ) {
        public FinalSummaryRequest {
            summaryResults = summaryResults == null ? List.of() : List.copyOf(summaryResults);
            synthesisInputs = synthesisInputs == null ? List.of() : List.copyOf(synthesisInputs);
        }
    }
}
