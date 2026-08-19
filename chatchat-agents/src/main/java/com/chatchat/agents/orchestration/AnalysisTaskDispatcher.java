package com.chatchat.agents.orchestration;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Driver port. A future distributed implementation can replace the local dispatcher. */
public interface AnalysisTaskDispatcher {

    DispatchBatch dispatch(
        List<AnalysisTask> tasks,
        AnalysisTaskWorker worker,
        BooleanSupplier cancellationCheck
    );

    @FunctionalInterface
    interface AnalysisTaskWorker {
        AnalysisSummaryResult execute(AnalysisTask task);
    }

    interface DispatchBatch extends AutoCloseable {
        AnalysisTaskResult await(String taskId);

        int taskCount();

        int workerCount();

        String mode();

        @Override
        void close();
    }
}
