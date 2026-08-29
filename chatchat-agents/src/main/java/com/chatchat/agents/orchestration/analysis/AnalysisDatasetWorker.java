package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.DataAnalysisPosition;
import com.chatchat.common.runtime.summary.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.ModelSummaryProgressReporter;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Executes one isolated dataset task: lossless chunking, spill/checkpoint recovery,
 * model retry and hierarchical dataset reduction. Driver orchestration deliberately
 * remains outside this worker.
 */
@Slf4j
public final class AnalysisDatasetWorker {

    private final AnalysisRecordChunkPlanner chunkPlanner;
    private final AnalysisSummaryCheckpointService checkpointService;
    private final AnalysisWorkerRetryPolicy retryPolicy;
    private final HierarchicalAnalysisReducer datasetReducer;
    private DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryProtocol;
    private AnalysisEvidenceSpillStore spillStore;

    public AnalysisDatasetWorker(
        AnalysisRecordChunkPlanner chunkPlanner,
        AnalysisSummaryCheckpointService checkpointService,
        AnalysisWorkerRetryPolicy retryPolicy,
        HierarchicalAnalysisReducer datasetReducer,
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryProtocol,
        AnalysisEvidenceSpillStore spillStore
    ) {
        this.chunkPlanner = chunkPlanner;
        this.checkpointService = checkpointService;
        this.retryPolicy = retryPolicy;
        this.datasetReducer = datasetReducer;
        this.summaryProtocol = summaryProtocol;
        this.spillStore = spillStore == null ? AnalysisEvidenceSpillStore.disabled() : spillStore;
    }

    public void setSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        if (protocol != null) this.summaryProtocol = protocol;
    }

    public void setSpillStore(AnalysisEvidenceSpillStore store) {
        this.spillStore = store == null ? AnalysisEvidenceSpillStore.disabled() : store;
    }

    public AnalysisDatasetSummary execute(ChatModel model,
                                          AnalysisTask task,
                                          ModelSummaryProgressReporter reporter,
                                          BooleanSupplier cancellationCheck,
                                          Runnable cancellationGuard) {
        ModelSummaryProgressReporter progress = reporter == null
            ? ModelSummaryProgressReporter.NOOP : reporter;
        Runnable guard = cancellationGuard == null ? () -> { } : cancellationGuard;
        AnalysisRecordChunkPlanner.ChunkPlan plan = chunkPlanner.plan(
            task.records(), task.maximumChunkRows(), task.maximumChunkChars());
        boolean modelSummaryRequired = summaryProtocol.requiresModelSummary(
            task.analysisContext(), plan.oversized());
        List<AnalysisDatasetSummary.ChunkResult> chunks = new ArrayList<>();
        int spilledChunkCount = 0;
        long spilledByteCount = 0;
        int restoredCheckpointCount = 0;
        int recordFrom = 1;
        for (int offset = 0; offset < plan.ranges().size(); offset++) {
            guard.run();
            AnalysisRecordChunkPlanner.Range range = plan.ranges().get(offset);
            List<Map<String, Object>> chunk = List.copyOf(task.records()
                .subList(range.fromInclusive(), range.toExclusive()));
            int recordTo = recordFrom + chunk.size() - 1;
            DataAnalysisPosition position = summaryProtocol.position(
                task.datasetReference(), offset + 1, plan.ranges().size(),
                recordFrom, recordTo, task.records().size());
            progress.report("CHUNK_STARTED", details(
                "chunkIndex", position.chunkIndex(), "chunkCount", position.chunkCount(),
                "recordFrom", recordFrom, "recordTo", recordTo, "recordCount", chunk.size()));

            String rawJson = ModelProtocolJson.compact(chunk);
            byte[] rawBytes = rawJson.getBytes(StandardCharsets.UTF_8);
            String contentSha256 = ModelProtocolJson.sha256Hex(rawJson);
            String evidenceId = task.isolationScope().partitionKey() + ":"
                + task.datasetReference() + "#chunk-" + (offset + 1);
            boolean spillRequested = spillStore.isEnabled()
                && (plan.oversized() || rawBytes.length >= task.spillThresholdBytes());
            AnalysisEvidenceSpillStore.SpillReference spillReference = null;
            if (spillRequested) {
                spillReference = spillStore.spill(
                    task.isolationScope(), evidenceId, contentSha256, rawBytes);
                spilledChunkCount++;
                spilledByteCount += spillReference.byteLength();
            }

            String checkpointInputSha256 = checkpointService.inputSha256(
                contentSha256, position, task.analysisContext(), task.originalUserQuestion(),
                modelSummaryRequired);
            String checkpointKey = task.datasetReference() + "#chunk-" + (offset + 1);
            AtomicInteger attemptCount = new AtomicInteger();
            AnalysisSummaryResult summary = spillReference == null ? null
                : checkpointService.restore(task.isolationScope(), checkpointKey,
                    checkpointInputSha256);
            boolean restoredCheckpoint = summary != null;
            if (restoredCheckpoint) restoredCheckpointCount++;
            if (summary == null) {
                summary = summarizeChunk(model, task, position, chunk, modelSummaryRequired,
                    attemptCount, progress, cancellationCheck);
                if (spillReference != null
                    && !"STRUCTURED_RECORD_FALLBACK".equals(summary.outcome())) {
                    checkpointService.persist(task.isolationScope(), checkpointKey,
                        checkpointInputSha256, summary);
                }
            }
            if (spillReference != null) {
                summary = summary.withEvidence(Map.of(
                    "rawReplayLocator", spillReference.toMap(),
                    "spillCheckpointKey", checkpointKey,
                    "spillCheckpointInputSha256", checkpointInputSha256));
            }
            summary = summary.withEvidence(Map.of(
                "workerAttemptCount", attemptCount.get(),
                "workerRetryCount", Math.max(0, attemptCount.get() - 1),
                "workerMaximumRetries", task.maximumRetries(),
                "workerMaximumAttempts", task.maximumAttempts()));
            chunks.add(new AnalysisDatasetSummary.ChunkResult(
                summary, spillReference, checkpointInputSha256, restoredCheckpoint,
                attemptCount.get()));
            progress.report("CHUNK_COMPLETED", details(
                "chunkIndex", position.chunkIndex(), "chunkCount", position.chunkCount(),
                "recordFrom", recordFrom, "recordTo", recordTo, "recordCount", chunk.size(),
                "attemptCount", attemptCount.get(), "restoredCheckpoint", restoredCheckpoint,
                "outcome", summary.outcome()));
            recordFrom = recordTo + 1;
        }
        return reduceDataset(model, task, plan, chunks, spilledChunkCount, spilledByteCount,
            restoredCheckpointCount, progress, cancellationCheck);
    }

    private AnalysisSummaryResult summarizeChunk(ChatModel model,
                                                  AnalysisTask task,
                                                  DataAnalysisPosition position,
                                                  List<Map<String, Object>> chunk,
                                                  boolean modelSummaryRequired,
                                                  AtomicInteger attemptCount,
                                                  ModelSummaryProgressReporter progress,
                                                  BooleanSupplier cancellationCheck) {
        if (!modelSummaryRequired) {
            return summaryProtocol.preserve(task.isolationScope(), position,
                task.analysisContext(), chunk);
        }
        return summaryProtocol.summarize(
            prompt -> retryPolicy.execute(task.maximumRetries(), cancellationCheck,
                ignored -> requireResponse(model.chat(prompt), "Analysis model"),
                attemptCount::set,
                (attempt, failure) -> {
                    log.warn("analysisChunkAttemptFailed dataset={} chunk={}/{} attempt={}/{} error={}",
                        task.datasetReference(), position.chunkIndex(), position.chunkCount(),
                        attempt, task.maximumAttempts(), failure.getMessage());
                    progress.report("CHUNK_RETRY", details(
                        "chunkIndex", position.chunkIndex(), "chunkCount", position.chunkCount(),
                        "failedAttempt", attempt, "maximumAttempts", task.maximumAttempts(),
                        "error", String.valueOf(failure.getMessage())));
                }),
            task.isolationScope(), position, task.analysisContext(), chunk,
            task.originalUserQuestion());
    }

    private AnalysisDatasetSummary reduceDataset(ChatModel model,
                                                  AnalysisTask task,
                                                  AnalysisRecordChunkPlanner.ChunkPlan plan,
                                                  List<AnalysisDatasetSummary.ChunkResult> chunks,
                                                  int spilledChunkCount,
                                                  long spilledByteCount,
                                                  int restoredCheckpointCount,
                                                  ModelSummaryProgressReporter progress,
                                                  BooleanSupplier cancellationCheck) {
        AtomicInteger attempts = new AtomicInteger();
        List<AnalysisSummaryResult> summaries = chunks.stream()
            .map(AnalysisDatasetSummary.ChunkResult::summary).toList();
        String checkpointKey = task.datasetReference() + "#dataset-reduce";
        String inputSha256 = ModelProtocolJson.sha256Hex(Map.of(
            "schemaVersion", HierarchicalAnalysisReducer.SCHEMA_VERSION,
            "datasetReference", task.datasetReference(),
            "analysisContext", task.analysisContext(),
            "originalUserQuestion", task.originalUserQuestion(),
            "chunkSummaries", summaries.stream().map(this::checkpointProjection).toList()));
        boolean checkpointEligible = spillStore.isEnabled() && plan.oversized();
        AnalysisSummaryResult datasetSummary = checkpointEligible
            ? checkpointService.restore(task.isolationScope(), checkpointKey, inputSha256) : null;
        boolean restored = datasetSummary != null;
        if (datasetSummary == null) {
            progress.report("DATASET_REDUCING", details(
                "chunkCount", summaries.size(),
                "originalQuestionPresent", !task.originalUserQuestion().isBlank()));
            datasetSummary = datasetReducer.reduceDataset(
                prompt -> retryPolicy.execute(task.maximumRetries(), cancellationCheck,
                    ignored -> requireResponse(model.chat(prompt), "Dataset reduction model"),
                    attempts::set,
                    (attempt, failure) -> {
                        log.warn("analysisDatasetReduceAttemptFailed dataset={} attempt={}/{} error={}",
                            task.datasetReference(), attempt, task.maximumAttempts(), failure.getMessage());
                        progress.report("DATASET_REDUCTION_RETRY", details(
                            "failedAttempt", attempt, "maximumAttempts", task.maximumAttempts(),
                            "error", String.valueOf(failure.getMessage())));
                    }),
                task.isolationScope(), task.datasetReference(), summaries,
                task.originalUserQuestion());
            if (checkpointEligible && !datasetSummary.outcome().contains("FALLBACK")) {
                checkpointService.persist(task.isolationScope(), checkpointKey, inputSha256,
                    datasetSummary);
            }
        }
        return AnalysisDatasetSummary.completed(task, plan.oversized(), plan.totalChars(), chunks,
            datasetSummary, spilledChunkCount, spilledByteCount, restoredCheckpointCount,
            attempts.get(), restored);
    }

    private Map<String, Object> checkpointProjection(AnalysisSummaryResult summary) {
        return Map.of(
            "resultId", summary.resultId(), "content", summary.content(),
            "outcome", summary.outcome(), "position", summary.position(),
            "analysisContext", summary.analysisContext(), "coverage", summary.coverage());
    }

    private String requireResponse(String value, String source) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(source + " returned an empty response");
        }
        return value;
    }

    private Map<String, Object> details(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
