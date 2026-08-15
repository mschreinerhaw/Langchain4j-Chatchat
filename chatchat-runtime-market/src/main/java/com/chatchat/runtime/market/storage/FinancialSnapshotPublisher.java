package com.chatchat.runtime.market.storage;

/** Publishes a consistent writer state for the physically isolated online read lane. */
@FunctionalInterface
public interface FinancialSnapshotPublisher {
    FinancialSnapshotPublisher NO_OP = () -> { };

    void publish();
}
