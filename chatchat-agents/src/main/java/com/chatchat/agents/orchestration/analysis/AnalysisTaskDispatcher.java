package com.chatchat.agents.orchestration.analysis;

import com.chatchat.common.runtime.summary.ModelSummaryDispatcher;
import com.chatchat.common.runtime.summary.ModelSummaryWorker;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Agent specialization of the Runtime OS distributed model-summary dispatcher. */
public interface AnalysisTaskDispatcher extends ModelSummaryDispatcher<
    AnalysisTask, AnalysisSummaryResult, AnalysisTaskResult> {

    @Override
    public DispatchBatch dispatch(
        List<AnalysisTask> tasks,
        ModelSummaryWorker<AnalysisTask, AnalysisSummaryResult> worker,
        BooleanSupplier cancellationCheck
    );

    public interface DispatchBatch extends ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> { }
}
