package com.chatchat.common.runtime.summary.spi;

import com.chatchat.common.runtime.summary.model.ModelSummary;
import com.chatchat.common.runtime.summary.model.ModelSummaryTask;
import com.chatchat.common.runtime.summary.model.ModelSummaryTaskResult;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runtime OS driver-to-worker boundary for distributed model summarization.
 * Transport, scheduling and worker placement remain implementation details.
 */
public interface ModelSummaryDispatcher<
    T extends ModelSummaryTask,
    S extends ModelSummary,
    R extends ModelSummaryTaskResult<S>
> extends RuntimeProtocolPort {

    default DispatchBatch<R> dispatch(
        List<T> tasks,
        ModelSummaryWorker<T, S> worker,
        BooleanSupplier cancellationCheck
    ) {
        return dispatch(tasks, worker, cancellationCheck, ModelSummaryProgressListener.NOOP);
    }

    DispatchBatch<R> dispatch(
        List<T> tasks,
        ModelSummaryWorker<T, S> worker,
        BooleanSupplier cancellationCheck,
        ModelSummaryProgressListener progressListener
    );

    interface DispatchBatch<R> extends AutoCloseable {
        R await(String taskId);

        int taskCount();

        int workerCount();

        String mode();

        /** Best-effort cancellation of one claimed or queued task. */
        boolean cancel(String taskId);

        /** True after the batch has released its local or remote resources. */
        boolean closed();

        @Override
        void close();
    }
}
