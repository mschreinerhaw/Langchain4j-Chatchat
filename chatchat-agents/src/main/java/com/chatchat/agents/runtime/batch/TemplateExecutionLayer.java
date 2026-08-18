package com.chatchat.agents.runtime.batch;

import com.chatchat.agents.runtime.ToolRuntimeExecution;

import java.util.List;

/**
 * MCP governance boundary for authorized template execution.
 *
 * <p>Template identity, parameter contracts, authorization and audit semantics
 * remain first-class here. Mechanical failure isolation is delegated to the
 * domain-neutral Kernel primitive.</p>
 */
public final class TemplateExecutionLayer {

    private final FailureIsolatedBatchExecutionLayer kernel =
        new FailureIsolatedBatchExecutionLayer();

    public List<Attempt> execute(List<ToolCallRequest> calls, TemplateInvoker invoker) {
        return kernel.execute(calls, (call, index) -> {
                Invocation invocation = invoker.invoke(call, index);
                if (invocation == null) {
                    return null;
                }
                if (invocation.execution() != null) {
                    return FailureIsolatedBatchExecutionLayer.Invocation.completed(invocation.execution());
                }
                if (invocation.terminal()) {
                    return FailureIsolatedBatchExecutionLayer.Invocation.terminal(
                        invocation.status(), invocation.errorCode(), invocation.message(),
                        invocation.remainingErrorCode(), invocation.remainingMessage());
                }
                return FailureIsolatedBatchExecutionLayer.Invocation.failed(
                    invocation.status(), invocation.errorCode(), invocation.message());
            }).stream()
            .map(this::governedAttempt)
            .toList();
    }

    private Attempt governedAttempt(FailureIsolatedBatchExecutionLayer.Attempt attempt) {
        return new Attempt(
            attempt.index(), attempt.call(), attempt.execution(), attempt.status(),
            templateErrorCode(attempt.errorCode()), templateMessage(attempt.message()));
    }

    private String templateErrorCode(String errorCode) {
        if (errorCode == null) return null;
        return switch (errorCode) {
            case "BATCH_CHILD_RUNTIME_ERROR" -> "TEMPLATE_CHILD_RUNTIME_ERROR";
            case "BATCH_CHILD_NO_RESULT" -> "TEMPLATE_CHILD_NO_RESULT";
            case "BATCH_CHILD_FAILED" -> "TEMPLATE_CHILD_FAILED";
            case "BATCH_EXECUTION_TERMINATED" -> "TEMPLATE_BATCH_TERMINATED";
            default -> errorCode;
        };
    }

    private String templateMessage(String message) {
        if ("Batch child invoker returned no execution result".equals(message)) {
            return "Template invoker returned no execution result";
        }
        if ("Batch child execution failed".equals(message)) {
            return "Template execution failed";
        }
        if ("Not executed because the Runtime can no longer invoke batch children".equals(message)) {
            return "Not executed because the Runtime can no longer invoke templates";
        }
        return message;
    }

    @FunctionalInterface
    public interface TemplateInvoker {
        Invocation invoke(ToolCallRequest call, int index);
    }

    public record Invocation(ToolRuntimeExecution execution, String status, String errorCode,
                             String message, boolean terminal, String remainingErrorCode,
                             String remainingMessage) {
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
        public boolean completed() {
            return execution != null;
        }
    }
}
