package com.chatchat.common.runtime.workflow;

import com.chatchat.common.kernel.KernelDataScope;

import java.util.UUID;

/** Template-method base defining invariant Runtime OS workflow lifecycle ordering. */
public abstract class AbstractRuntimeWorkflow<I, O> implements RuntimeWorkflow<I, O> {
    @Override
    public final O execute(I input) {
        return execute(input, KernelDataScope.system(UUID.randomUUID().toString()));
    }

    @Override
    public final O execute(I input, KernelDataScope scope) {
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        validateInput(input, scope);
        beforeExecution(input, scope);
        try {
            O output = doExecute(input, scope);
            afterExecution(input, output, scope);
            return output;
        } catch (RuntimeException | Error error) {
            onExecutionFailure(input, error, scope);
            throw error;
        }
    }

    protected void validateInput(I input, KernelDataScope scope) { validateInput(input); }
    protected void beforeExecution(I input, KernelDataScope scope) { beforeExecution(input); }
    protected O doExecute(I input, KernelDataScope scope) { return doExecute(input); }
    protected void afterExecution(I input, O output, KernelDataScope scope) { afterExecution(input, output); }
    protected void onExecutionFailure(I input, Throwable error, KernelDataScope scope) {
        onExecutionFailure(input, error);
    }

    /** Compatibility hooks for existing workflow implementations. */
    protected void validateInput(I input) { }
    protected void beforeExecution(I input) { }
    protected abstract O doExecute(I input);
    protected void afterExecution(I input, O output) { }
    protected void onExecutionFailure(I input, Throwable error) { }
}
