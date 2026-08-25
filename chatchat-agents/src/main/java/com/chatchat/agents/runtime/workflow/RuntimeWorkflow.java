package com.chatchat.agents.runtime.workflow;

/**
 * Stable Runtime OS port for an executable workflow.
 *
 * @param <I> workflow input contract
 * @param <O> workflow output contract
 */
@FunctionalInterface
public interface RuntimeWorkflow<I, O> {

    O execute(I input);
}
