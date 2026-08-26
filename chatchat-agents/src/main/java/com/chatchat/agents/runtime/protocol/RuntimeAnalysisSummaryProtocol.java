package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.protocol.AgentProtocolCatalog;
import com.chatchat.agents.runtime.GovernanceIsolationScope;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import com.chatchat.common.runtime.summary.ModelSummaryModel;

import java.util.List;
import java.util.Map;

/** Governs record analysis, preservation, lineage and final synthesis products. */
public interface RuntimeAnalysisSummaryProtocol<S extends RuntimeAnalysisSummary>
    extends RuntimeProtocolPort {

    String BRIDGE_SCHEMA_VERSION = AgentProtocolCatalog.RUNTIME_ANALYSIS_SUMMARY;
    String EVIDENCE_SCHEMA_VERSION = "traceable_chunk_evidence.v1";

    boolean requiresModelSummary(Map<String, Object> governedContext, boolean oversized);

    Map<String, Object> govern(String reference,
                               Map<String, Object> suppliedContext,
                               List<Map<String, Object>> records);

    RuntimeAnalysisPosition position(String reference, int chunkIndex, int chunkCount,
                                     int from, int to, int totalRecords);

    S summarize(ModelSummaryModel model, GovernanceIsolationScope isolationScope,
                RuntimeAnalysisPosition position, Map<String, Object> governedContext,
                List<Map<String, Object>> records);

    S summarize(ModelSummaryModel model, GovernanceIsolationScope isolationScope,
                RuntimeAnalysisPosition position, Map<String, Object> governedContext,
                List<Map<String, Object>> records, String userObjective);

    S preserve(GovernanceIsolationScope isolationScope, RuntimeAnalysisPosition position,
               Map<String, Object> governedContext, List<Map<String, Object>> records);

    S fallback(GovernanceIsolationScope isolationScope, RuntimeAnalysisPosition position,
               Map<String, Object> governedContext, List<Map<String, Object>> records);

    String finalSynthesisInstruction();

    Map<String, Object> ledger(List<S> summaries, int returnedRecordCount,
                               int processedRecordCount, boolean complete);

    S finalResult(GovernanceIsolationScope isolationScope, String stage, String content,
                  String outcome, Map<String, Object> coverage, List<S> inputs);

    S finalResult(GovernanceIsolationScope isolationScope, String stage, String content,
                  String outcome, Map<String, Object> coverage, List<S> inputs,
                  List<String> upstreamResultIds);
}
