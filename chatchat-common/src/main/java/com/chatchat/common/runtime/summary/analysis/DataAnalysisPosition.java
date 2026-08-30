package com.chatchat.common.runtime.summary.analysis;

import java.util.Map;

/** Stable, serializable location of one summary chunk in a logical dataset. */
public record DataAnalysisPosition(
    String datasetReference,
    int chunkIndex,
    int chunkCount,
    int recordFrom,
    int recordTo,
    int totalRecords
) {
    public Map<String, Object> toMap() {
        return Map.of(
            "datasetReference", datasetReference,
            "chunkIndex", chunkIndex,
            "chunkCount", chunkCount,
            "recordFrom", recordFrom,
            "recordTo", recordTo,
            "totalRecords", totalRecords,
            "recordPath", datasetReference + ".records[" + recordFrom + ".." + recordTo + "]"
        );
    }
}
