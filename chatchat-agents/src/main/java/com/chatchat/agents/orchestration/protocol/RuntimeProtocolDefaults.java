package com.chatchat.agents.orchestration.protocol;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.worker.AnalysisSummaryGovernanceBridge;


import com.chatchat.agents.orchestration.tool.McpAnalysisContextAdapter;
import com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisContextProtocol;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Upper-layer default composition for non-Spring construction and compatibility tests. */
public final class RuntimeProtocolDefaults {

    private RuntimeProtocolDefaults() {
    }

    public static RuntimeResultAnalysisProtocol resultAnalysis() {
        return new McpResultAnalysisBridge();
    }

    public static RuntimeResultAnalysisProtocol resultAnalysis(
        List<RuntimeResultAnalysisAdapter> adapters
    ) {
        return new McpResultAnalysisBridge(adapters);
    }

    public static RuntimeAnalysisContextProtocol analysisContext(ObjectMapper objectMapper) {
        return new McpAnalysisContextAdapter(objectMapper);
    }

    public static DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> analysisSummary() {
        return new AnalysisSummaryGovernanceBridge();
    }
}
