package com.chatchat.agents.orchestration.analysis;

import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryWorker;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Agent specialization of the Runtime OS distributed model-summary dispatcher. */
public interface AnalysisTaskDispatcher extends ModelSummaryDispatcher<
    AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> {

    @Override
    public DispatchBatch dispatch(
        List<AnalysisTask> tasks,
        ModelSummaryWorker<AnalysisTask, AnalysisDatasetSummary> worker,
        BooleanSupplier cancellationCheck
    );

    /**
     * Progress-aware transport contract. Implementations should preserve progress when moving
     * Workers out of process; the default keeps older transports source compatible.
     */
    default DispatchBatch dispatch(
        List<AnalysisTask> tasks,
        AnalysisTaskWorker worker,
        BooleanSupplier cancellationCheck,
        AnalysisTaskProgressListener progressListener
    ) {
        return dispatch(tasks, task -> worker.execute(task, AnalysisTaskProgressReporter.NOOP),
            cancellationCheck);
    }

    public interface DispatchBatch extends ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> { }
}
