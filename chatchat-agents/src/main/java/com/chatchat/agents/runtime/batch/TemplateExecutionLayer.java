package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.ToolRuntimeExecution;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime-owned execution boundary for authorized template commands.
 *
 * <p>The layer has one responsibility: give every compiled template call an
 * execution result slot. A child validation or invocation failure is isolated
 * to that child and never prevents a later template from running. Only a
 * terminal runtime condition, such as cancellation or an exhausted deadline,
 * may stop remote invocation; remaining calls are then returned explicitly as
 * {@code NOT_EXECUTED} instead of disappearing from the batch.</p>
 */
public final class TemplateExecutionLayer {

    public List<Attempt> execute(List<ToolCallRequest> calls, TemplateInvoker invoker) {
        List<ToolCallRequest> safeCalls = calls == null ? List.of() : List.copyOf(calls);
        List<Attempt> attempts = new ArrayList<>(safeCalls.size());
        TerminalFailure terminalFailure = null;
        for (int index = 0; index < safeCalls.size(); index++) {
            ToolCallRequest call = safeCalls.get(index);
            if (terminalFailure != null) {
                attempts.add(Attempt.failure(
                    index,
                    call,
                    "NOT_EXECUTED",
                    terminalFailure.remainingErrorCode(),
                    terminalFailure.remainingMessage()
                ));
                continue;
            }
            Invocation invocation;
            try {
                invocation = invoker.invoke(call, index);
            } catch (RuntimeException ex) {
                attempts.add(Attempt.failure(
                    index,
                    call,
                    "FAILED",
                    "TEMPLATE_CHILD_RUNTIME_ERROR",
                    firstText(ex.getMessage(), ex.getClass().getSimpleName())
                ));
                continue;
            }
            if (invocation == null) {
                attempts.add(Attempt.failure(
                    index,
                    call,
                    "FAILED",
                    "TEMPLATE_CHILD_NO_RESULT",
                    "Template invoker returned no execution result"
                ));
                continue;
            }
            if (invocation.execution() != null) {
                attempts.add(Attempt.completed(index, call, invocation.execution()));
                continue;
            }
            attempts.add(Attempt.failure(
                index,
                call,
                firstText(invocation.status(), "FAILED"),
                firstText(invocation.errorCode(), "TEMPLATE_CHILD_FAILED"),
                firstText(invocation.message(), "Template execution failed")
            ));
            if (invocation.terminal()) {
                terminalFailure = new TerminalFailure(
                    firstText(invocation.remainingErrorCode(), "TEMPLATE_BATCH_TERMINATED"),
                    firstText(invocation.remainingMessage(),
                        "Not executed because the Runtime can no longer invoke templates")
                );
            }
        }
        return List.copyOf(attempts);
    }

    @FunctionalInterface
    public interface TemplateInvoker {
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

        public static Invocation terminal(String status,
                                          String errorCode,
                                          String message,
                                          String remainingErrorCode,
                                          String remainingMessage) {
            return new Invocation(
                null, status, errorCode, message, true, remainingErrorCode, remainingMessage);
        }
    }

    public record Attempt(
        int index,
        ToolCallRequest call,
        ToolRuntimeExecution execution,
        String status,
        String errorCode,
        String message
    ) {
        private static Attempt completed(int index,
                                         ToolCallRequest call,
                                         ToolRuntimeExecution execution) {
            return new Attempt(index, call, execution, null, null, null);
        }

        private static Attempt failure(int index,
                                       ToolCallRequest call,
                                       String status,
                                       String errorCode,
                                       String message) {
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
