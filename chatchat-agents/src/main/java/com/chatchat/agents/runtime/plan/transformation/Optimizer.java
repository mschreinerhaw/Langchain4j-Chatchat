package com.chatchat.agents.runtime.plan.transformation;

/** Generic copy-on-write optimizer contract. */
@FunctionalInterface
public interface Optimizer<S, C, R> {

    R optimize(S source, C context);
}
