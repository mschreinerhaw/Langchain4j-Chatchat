package com.chatchat.common.runtime.workflow;

/** Template-method base defining invariant Runtime OS workflow lifecycle ordering. */
public abstract class AbstractRuntimeWorkflow<I, O> implements RuntimeWorkflow<I, O> {
    @Override
    public final O execute(I input) {
        validateInput(input);
        beforeExecution(input);
        try {
            O output = doExecute(input);
            afterExecution(input, output);
            return output;
        } catch (RuntimeException | Error error) {
            onExecutionFailure(input, error);
            throw error;
        }
    }

    protected void validateInput(I input) { }
    protected void beforeExecution(I input) { }
    protected abstract O doExecute(I input);
    protected void afterExecution(I input, O output) { }
    protected void onExecutionFailure(I input, Throwable error) { }
}
