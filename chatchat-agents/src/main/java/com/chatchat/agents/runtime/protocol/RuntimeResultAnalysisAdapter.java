package com.chatchat.agents.runtime.protocol;

import java.util.List;
import java.util.Map;

/**
 * Extension point from one tool result contract to canonical model-analysis datasets.
 * Implementations select by protocol characteristics, never by business tool names.
 */
public interface RuntimeResultAnalysisAdapter extends RuntimeProtocolPort {

    String id();

    default int priority() {
        return 0;
    }

    default boolean fallback() {
        return false;
    }

    boolean supports(AnalysisRequest request);

    AnalysisResult adapt(AnalysisRequest request);

    record AnalysisRequest(String datasetReference, Object payload, int maximumRecordChars) {
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
