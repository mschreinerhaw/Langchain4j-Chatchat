package com.chatchat.common.runtime.workflow;

/** Policy port evaluated at a Runtime OS workflow boundary. */
@FunctionalInterface
public interface RuntimeWorkflowGuard<C, R> {
    R evaluate(C context);
}
