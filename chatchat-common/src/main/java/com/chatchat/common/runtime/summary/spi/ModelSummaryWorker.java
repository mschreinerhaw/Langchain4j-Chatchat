package com.chatchat.common.runtime.summary.spi;

import com.chatchat.common.runtime.summary.model.ModelSummary;
import com.chatchat.common.runtime.summary.model.ModelSummaryTask;

/** Replaceable compute worker; implementations may run locally or behind RPC/message middleware. */
@FunctionalInterface
public interface ModelSummaryWorker<T extends ModelSummaryTask, S extends ModelSummary> {

    S execute(T task, ModelSummaryProgressReporter progressReporter);

    default S execute(T task) {
        return execute(task, ModelSummaryProgressReporter.NOOP);
    }
}
