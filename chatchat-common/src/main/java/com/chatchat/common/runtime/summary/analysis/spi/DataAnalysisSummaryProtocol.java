package com.chatchat.common.runtime.summary.analysis.spi;

import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisIsolationScope;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisSummary;


import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;

/**
 * Common Runtime OS port for governed model-assisted data-analysis summarization.
 * Implementations own prompting and governance; callers depend only on this contract.
 */
public interface DataAnalysisSummaryProtocol<
    S extends DataAnalysisSummary,
    I extends DataAnalysisIsolationScope
> extends RuntimeProtocolPort {

    String BRIDGE_SCHEMA_VERSION = "analysis_summary_bridge.v1";
    String EVIDENCE_SCHEMA_VERSION = "traceable_chunk_evidence.v1";

    Map<String, Object> govern(String reference, Map<String, Object> suppliedContext,
                               List<Map<String, Object>> records);

    Map<String, Object> ledger(List<S> summaries, int returnedRecordCount,
                               int processedRecordCount, boolean complete);

    S finalResult(I isolationScope, String stage, String content,
                  String outcome, Map<String, Object> coverage, List<S> inputs);

    S finalResult(I isolationScope, String stage, String content,
                  String outcome, Map<String, Object> coverage, List<S> inputs,
                  List<String> upstreamResultIds);
}
