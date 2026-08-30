package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.common.runtime.summary.ModelSummaryProgress;

import java.util.List;
import java.util.Map;

/** Durable result and externally visible business progress for an analysis batch. */
public record TemporalAnalysisBatchResult(
    String batchId,
    String status,
    Map<String, AnalysisTaskResult> results,
    List<ModelSummaryProgress> progress
) {
    public TemporalAnalysisBatchResult {
        batchId = batchId == null ? "" : batchId;
        status = status == null ? "PENDING" : status;
        results = results == null ? Map.of() : Map.copyOf(results);
        progress = progress == null ? List.of() : List.copyOf(progress);
    }
}
