package com.chatchat.agents.orchestration.analysis.governance;

import java.util.List;
import java.util.Map;

/** Immutable, user-visible accounting for every dataset admitted to analysis. */
public record DatasetCompletionSnapshot(
    int expectedDatasetCount,
    int excludedDatasetCount,
    int analyzedDatasetCount,
    int failedDatasetCount,
    List<String> successfulDatasetReferences,
    List<String> failedDatasetReferences,
    List<String> excludedDatasetReferences
) {
    public DatasetCompletionSnapshot {
        expectedDatasetCount = Math.max(0, expectedDatasetCount);
        excludedDatasetCount = Math.max(0, excludedDatasetCount);
        analyzedDatasetCount = Math.max(0, analyzedDatasetCount);
        failedDatasetCount = Math.max(0, failedDatasetCount);
        successfulDatasetReferences = copy(successfulDatasetReferences);
        failedDatasetReferences = copy(failedDatasetReferences);
        excludedDatasetReferences = copy(excludedDatasetReferences);
    }

    public boolean allRequiredDatasetsProcessed() {
        return analyzedDatasetCount + failedDatasetCount + excludedDatasetCount
            == expectedDatasetCount;
    }

    public boolean partial() {
        return analyzedDatasetCount > 0 && (failedDatasetCount > 0 || excludedDatasetCount > 0);
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "expectedDatasetCount", expectedDatasetCount,
            "excludedDatasetCount", excludedDatasetCount,
            "analyzedDatasetCount", analyzedDatasetCount,
            "failedDatasetCount", failedDatasetCount,
            "successfulDatasetReferences", successfulDatasetReferences,
            "failedDatasetReferences", failedDatasetReferences,
            "excludedDatasetReferences", excludedDatasetReferences,
            "allRequiredDatasetsProcessed", allRequiredDatasetsProcessed(),
            "partial", partial());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }
}
