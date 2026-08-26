package com.chatchat.common.runtime.summary;

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

    DispatchBatch<R> dispatch(
        List<T> tasks,
        ModelSummaryWorker<T, S> worker,
        BooleanSupplier cancellationCheck
    );

    interface DispatchBatch<R> extends AutoCloseable {
        R await(String taskId);

        int taskCount();

        int workerCount();

        String mode();

        @Override
        void close();
    }
}
