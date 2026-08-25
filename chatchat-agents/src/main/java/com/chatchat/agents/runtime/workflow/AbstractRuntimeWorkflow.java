package com.chatchat.agents.runtime.workflow;

/**
 * Template-method base for Runtime OS workflow implementations.
 *
 * <p>The stable entry point and lifecycle ordering live here; concrete workflows own only
 * validation and execution policy. Subclasses may observe lifecycle hooks without replacing the
 * execution contract.</p>
 */
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

    protected void validateInput(I input) {
        // Optional invariant hook.
    }

    protected void beforeExecution(I input) {
        // Optional lifecycle hook.
    }

    protected abstract O doExecute(I input);

    protected void afterExecution(I input, O output) {
        // Optional lifecycle hook.
    }

    protected void onExecutionFailure(I input, Throwable error) {
        // Optional lifecycle hook.
    }
}
