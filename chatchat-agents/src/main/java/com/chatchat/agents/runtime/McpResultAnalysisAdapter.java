package com.chatchat.agents.runtime;

import java.util.List;
import java.util.Map;

/**
 * Adapts an MCP result contract into model-analyzable record datasets.
 *
 * <p>Implementations must select results by protocol characteristics, never by a tool's
 * business name. They must preserve all facts they claim to project and must not mutate the
 * authoritative Runtime payload.</p>
 */
public interface McpResultAnalysisAdapter {

    /** Stable adapter identity included in the derived projection for auditability. */
    String id();

    /** Higher-priority adapters are considered first. */
    default int priority() {
        return 0;
    }

    /** Whether this adapter is the catch-all used only after protocol-specific adapters. */
    default boolean fallback() {
        return false;
    }

    /** Whether this adapter understands the supplied result contract. */
    boolean supports(AnalysisRequest request);

    /** Converts the result into the canonical analysis dataset contract. */
    AnalysisResult adapt(AnalysisRequest request);

    record AnalysisRequest(String datasetReference,
                           Object payload,
                           int maximumRecordChars) {
    }

    record AnalysisResult(String sourceSchemaVersion,
                          String evidenceRole,
                          List<AnalysisDataset> datasets) {

        public AnalysisResult {
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
        }
    }

    record AnalysisDataset(String datasetReference,
                           Map<String, Object> analysisContext,
                           List<Map<String, Object>> records) {

        public AnalysisDataset {
            analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
            records = records == null ? List.of() : List.copyOf(records);
        }
    }
}
