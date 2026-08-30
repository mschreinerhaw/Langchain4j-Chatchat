package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;

/**
 * Stable server-side execution boundary for one serialized dataset analysis task.
 * Transport adapters invoke this port instead of attempting to serialize a model callback.
 */
@FunctionalInterface
public interface AnalysisDatasetExecutionPort {

    AnalysisTaskResult execute(
        AnalysisTask task,
        ModelSummaryProgressReporter progressReporter
    );
}
