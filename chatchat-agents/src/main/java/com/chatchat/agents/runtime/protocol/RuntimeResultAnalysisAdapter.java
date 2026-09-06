package com.chatchat.agents.runtime.protocol;

import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.InMemoryDatasetHandle;
import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

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

    final class AnalysisDataset {
        private final String datasetReference;
        private final Map<String, Object> analysisContext;
        private final DatasetHandle handle;

        public AnalysisDataset(String datasetReference, Map<String, Object> analysisContext,
                               List<Map<String, Object>> records) {
            this(datasetReference, analysisContext, new InMemoryDatasetHandle(records));
        }

        public AnalysisDataset(String datasetReference, Map<String, Object> analysisContext,
                               DatasetHandle handle) {
            this.datasetReference = datasetReference;
            this.analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
            this.handle = java.util.Objects.requireNonNull(handle, "dataset handle");
        }

        public String datasetReference() { return datasetReference; }
        public Map<String, Object> analysisContext() { return analysisContext; }
        public DatasetHandle handle() { return handle; }
        /** Compatibility view; consumers should prefer {@link #handle()}. */
        public List<Map<String, Object>> records() { return handle.asListView(); }
    }
}
