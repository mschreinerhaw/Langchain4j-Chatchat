package com.chatchat.agents.orchestration.analysis;

/** Analysis Worker that can report progress while computing its assigned dataset. */
@FunctionalInterface
public interface AnalysisTaskWorker {

    AnalysisDatasetSummary execute(
        AnalysisTask task,
        AnalysisTaskProgressReporter progressReporter
    );
}
