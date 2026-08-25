package com.chatchat.agents.runtime.workflow;

/** Policy port evaluated at a workflow boundary. */
@FunctionalInterface
public interface RuntimeWorkflowGuard<C, R> {

    R evaluate(C context);
}
