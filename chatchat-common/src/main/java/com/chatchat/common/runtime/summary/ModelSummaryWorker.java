package com.chatchat.common.runtime.summary;

/** Replaceable compute worker; implementations may run locally or behind RPC/message middleware. */
@FunctionalInterface
public interface ModelSummaryWorker<T extends ModelSummaryTask, S extends ModelSummary> {

    S execute(T task);
}
