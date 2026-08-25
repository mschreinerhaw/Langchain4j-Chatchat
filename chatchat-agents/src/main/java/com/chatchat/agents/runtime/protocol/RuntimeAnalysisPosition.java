package com.chatchat.agents.runtime.protocol;

import java.util.Map;

/** Stable location of one analysis chunk within a Runtime evidence dataset. */
public record RuntimeAnalysisPosition(String datasetReference,
                                      int chunkIndex,
                                      int chunkCount,
                                      int recordFrom,
                                      int recordTo,
                                      int totalRecords) {
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
