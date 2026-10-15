package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Runtime owns persistence, authorization and budgets. Implementations must not create a parallel control plane. */
public interface CodingAgentSPI {
    CodingTaskOutcome submit(CodingTaskContract task);
    CodingTaskOutcome get(KernelDataScope scope, String taskId);
    CodingTaskOutcome cancel(KernelDataScope scope, String taskId, String reason);
    CodingTaskOutcome resume(KernelDataScope scope, String taskId);

    /** Replay persisted events after the cursor, then tail. Delivery is at least once; never executes code. */
    Subscription subscribe(KernelDataScope scope, String taskId, long afterSequence, Observer observer);

    interface Subscription extends AutoCloseable {
        /** Idempotently closes this listener, not the task. */
        @Override void close();
    }
    interface Observer {
        void onEvent(CodingExecutionEvent event);
        /** Terminal subscription failure; reconnect only from the last processed sequence. */
        void onError(Error error);
        void onClosed();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(CodingErrorCode code, boolean retryable, String taskId, String operationId,
                 String correlationId, String knownOutcomeRef, String message) {
        public Error {
            CodingProtocol.required(code, "code");
            taskId = CodingProtocol.optionalRef(taskId, "taskId");
            operationId = CodingProtocol.optionalRef(operationId, "operationId");
            correlationId = CodingProtocol.optionalRef(correlationId, "correlationId");
            knownOutcomeRef = CodingProtocol.optionalRef(knownOutcomeRef, "knownOutcomeRef");
            message = CodingProtocol.text(message, "message", 4096);
        }
    }
}
