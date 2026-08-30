package com.chatchat.agents.orchestration.analysis.model;

import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete result produced by one worker for one dataset. */
public record AnalysisDatasetSummary(
    String schemaVersion,
    String resultId,
    String content,
    String outcome,
    GovernanceIsolationScope isolationScope,
    String datasetReference,
    int totalRecords,
    boolean oversized,
    long serializedChars,
    List<ChunkResult> chunks,
    AnalysisSummaryResult datasetSummary,
    int spilledChunkCount,
    long spilledByteCount,
    int restoredCheckpointCount,
    int retriedChunkCount,
    int totalRetryCount,
    int datasetReductionAttemptCount,
    boolean datasetReductionRestoredCheckpoint,
    List<String> inputSummaryResultIds,
    Map<String, Object> evidence
) implements ModelSummary {
    public static final String SCHEMA_VERSION = "analysis_dataset_summary.v1";

    public AnalysisDatasetSummary {
        schemaVersion = SCHEMA_VERSION;
        resultId = resultId == null ? "dataset-summary" : resultId;
        content = content == null ? "" : content;
        outcome = outcome == null ? "UNKNOWN" : outcome;
        isolationScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null) : isolationScope;
        datasetReference = datasetReference == null ? "result" : datasetReference;
        totalRecords = Math.max(0, totalRecords);
        serializedChars = Math.max(0, serializedChars);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (datasetSummary == null) {
            throw new IllegalArgumentException("datasetSummary is required");
        }
        spilledChunkCount = Math.max(0, spilledChunkCount);
        spilledByteCount = Math.max(0, spilledByteCount);
        restoredCheckpointCount = Math.max(0, restoredCheckpointCount);
        retriedChunkCount = Math.max(0, retriedChunkCount);
        totalRetryCount = Math.max(0, totalRetryCount);
        datasetReductionAttemptCount = Math.max(0, datasetReductionAttemptCount);
        inputSummaryResultIds = inputSummaryResultIds == null
            ? chunks.stream().map(ChunkResult::summary).map(AnalysisSummaryResult::resultId).toList()
            : List.copyOf(inputSummaryResultIds);
        evidence = evidence == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    public static AnalysisDatasetSummary completed(
        AnalysisTask task, boolean oversized, long serializedChars, List<ChunkResult> chunks,
        AnalysisSummaryResult datasetSummary, int spilledChunkCount, long spilledByteCount,
        int restoredCheckpointCount, int datasetReductionAttemptCount,
        boolean datasetReductionRestoredCheckpoint
    ) {
        List<ChunkResult> safeChunks = chunks == null ? List.of() : List.copyOf(chunks);
        String outcome = safeChunks.stream().anyMatch(chunk ->
            "STRUCTURED_RECORD_FALLBACK".equals(chunk.summary().outcome()))
            || datasetSummary.outcome().contains("FALLBACK") ? "FALLBACK" : "SUCCESS";
        return new AnalysisDatasetSummary(
            SCHEMA_VERSION,
            task.isolationScope().partitionKey() + ":" + task.datasetReference() + "#dataset",
            datasetSummary.content(),
            outcome, task.isolationScope(), task.datasetReference(), task.records().size(),
            oversized, serializedChars, safeChunks, datasetSummary,
            spilledChunkCount, spilledByteCount,
            restoredCheckpointCount,
            (int) safeChunks.stream().filter(chunk -> chunk.retryCount() > 0).count(),
            safeChunks.stream().mapToInt(ChunkResult::retryCount).sum()
                + Math.max(0, datasetReductionAttemptCount - 1),
            datasetReductionAttemptCount,
            datasetReductionRestoredCheckpoint,
            safeChunks.stream().map(ChunkResult::summary).map(AnalysisSummaryResult::resultId).toList(),
            Map.of(
                "schemaVersion", "dataset_worker_evidence.v1",
                "chunkCount", safeChunks.size(),
                "workerOwnedChunking", true,
                "retriedChunkCount",
                    safeChunks.stream().filter(chunk -> chunk.retryCount() > 0).count(),
                "totalRetryCount", safeChunks.stream().mapToInt(ChunkResult::retryCount).sum()
                    + Math.max(0, datasetReductionAttemptCount - 1)
            ));
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("resultId", resultId);
        value.put("content", content);
        value.put("outcome", outcome);
        value.put("isolationScope", isolationScope.toMap());
        value.put("datasetReference", datasetReference);
        value.put("totalRecords", totalRecords);
        value.put("oversized", oversized);
        value.put("serializedChars", serializedChars);
        value.put("chunks", chunks.stream().map(ChunkResult::toMap).toList());
        value.put("datasetSummary", datasetSummary.toMap());
        value.put("spilledChunkCount", spilledChunkCount);
        value.put("spilledByteCount", spilledByteCount);
        value.put("restoredCheckpointCount", restoredCheckpointCount);
        value.put("retriedChunkCount", retriedChunkCount);
        value.put("totalRetryCount", totalRetryCount);
        value.put("datasetReductionAttemptCount", datasetReductionAttemptCount);
        value.put("datasetReductionRetryCount", Math.max(0, datasetReductionAttemptCount - 1));
        value.put("datasetReductionRestoredCheckpoint", datasetReductionRestoredCheckpoint);
        value.put("inputSummaryResultIds", inputSummaryResultIds);
        value.put("evidence", evidence);
        return Collections.unmodifiableMap(value);
    }

    public record ChunkResult(
        AnalysisSummaryResult summary,
        AnalysisEvidenceSpillStore.SpillReference spillReference,
        String checkpointInputSha256,
        boolean restoredCheckpoint,
        int attemptCount
    ) {
        public ChunkResult {
            if (summary == null) throw new IllegalArgumentException("summary is required");
            checkpointInputSha256 = checkpointInputSha256 == null ? "" : checkpointInputSha256;
            attemptCount = Math.max(0, attemptCount);
        }

        public int retryCount() {
            return Math.max(0, attemptCount - 1);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("summary", summary.toMap());
            value.put("spillReference", spillReference == null ? Map.of() : spillReference.toMap());
            value.put("checkpointInputSha256", checkpointInputSha256);
            value.put("restoredCheckpoint", restoredCheckpoint);
            value.put("attemptCount", attemptCount);
            value.put("retryCount", retryCount());
            return Map.copyOf(value);
        }
    }
}
