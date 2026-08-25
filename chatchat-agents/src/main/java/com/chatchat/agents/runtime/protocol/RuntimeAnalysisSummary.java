package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.runtime.GovernanceIsolationScope;

import java.util.List;
import java.util.Map;

/** Read-only contract for a governed analysis product and its evidence lineage. */
public interface RuntimeAnalysisSummary {
    String schemaVersion();
    String resultId();
    String scope();
    String content();
    String outcome();
    GovernanceIsolationScope isolationScope();
    Map<String, Object> position();
    Map<String, Object> analysisContext();
    Map<String, Object> coverage();
    List<String> inputSummaryResultIds();
    Map<String, Object> evidence();
    Map<String, Object> governance();
    Map<String, Object> toMap();
}
