package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;

import java.util.ArrayList;
import java.util.List;

/**
 * Kernel execution boundary that gives every admitted batch child a terminal slot.
 *
 * <p>Child validation and invocation failures are isolated. Only a terminal
 * runtime condition can stop remote invocation, and every remaining child is
 * then represented explicitly as {@code NOT_EXECUTED}.</p>
 */
public final class FailureIsolatedBatchExecutionLayer {

    public List<Attempt> execute(List<ToolCallRequest> calls, ChildInvoker invoker) {
        List<ToolCallRequest> safeCalls = calls == null ? List.of() : List.copyOf(calls);
        List<Attempt> attempts = new ArrayList<>(safeCalls.size());
        TerminalFailure terminalFailure = null;
        for (int index = 0; index < safeCalls.size(); index++) {
            ToolCallRequest call = safeCalls.get(index);
            if (terminalFailure != null) {
                attempts.add(Attempt.failure(index, call, "NOT_EXECUTED",
                    terminalFailure.remainingErrorCode(), terminalFailure.remainingMessage()));
                continue;
            }
            Invocation invocation;
            try {
                invocation = invoker.invoke(call, index);
            } catch (RuntimeException ex) {
                attempts.add(Attempt.failure(index, call, "FAILED", "BATCH_CHILD_RUNTIME_ERROR",
                    firstText(ex.getMessage(), ex.getClass().getSimpleName())));
                continue;
            }
            if (invocation == null) {
                attempts.add(Attempt.failure(index, call, "FAILED", "BATCH_CHILD_NO_RESULT",
                    "Batch child invoker returned no execution result"));
                continue;
            }
            if (invocation.execution() != null) {
                attempts.add(Attempt.completed(index, call, invocation.execution()));
                continue;
            }
            attempts.add(Attempt.failure(index, call,
                firstText(invocation.status(), "FAILED"),
                firstText(invocation.errorCode(), "BATCH_CHILD_FAILED"),
                firstText(invocation.message(), "Batch child execution failed")));
            if (invocation.terminal()) {
                terminalFailure = new TerminalFailure(
                    firstText(invocation.remainingErrorCode(), "BATCH_EXECUTION_TERMINATED"),
                    firstText(invocation.remainingMessage(),
                        "Not executed because the Runtime can no longer invoke batch children"));
            }
        }
        return List.copyOf(attempts);
    }

    @FunctionalInterface
    public interface ChildInvoker {
        Invocation invoke(ToolCallRequest call, int index);
    }

    public record Invocation(
        ToolRuntimeExecution execution,
        String status,
        String errorCode,
        String message,
        boolean terminal,
        String remainingErrorCode,
        String remainingMessage
    ) {
        public static Invocation completed(ToolRuntimeExecution execution) {
            return new Invocation(execution, null, null, null, false, null, null);
        }

        public static Invocation failed(String status, String errorCode, String message) {
            return new Invocation(null, status, errorCode, message, false, null, null);
        }

        public static Invocation terminal(String status, String errorCode, String message,
                                          String remainingErrorCode, String remainingMessage) {
            return new Invocation(null, status, errorCode, message, true,
                remainingErrorCode, remainingMessage);
        }
    }

    public record Attempt(int index, ToolCallRequest call, ToolRuntimeExecution execution,
                          String status, String errorCode, String message) {
        private static Attempt completed(int index, ToolCallRequest call,
                                         ToolRuntimeExecution execution) {
            return new Attempt(index, call, execution, null, null, null);
        }

        private static Attempt failure(int index, ToolCallRequest call, String status,
                                       String errorCode, String message) {
            return new Attempt(index, call, null, status, errorCode, message);
        }

        public boolean completed() {
            return execution != null;
        }
    }

    private record TerminalFailure(String remainingErrorCode, String remainingMessage) {
    }

    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
