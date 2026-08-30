package com.chatchat.common.runtime.summary.spi;

import java.util.Map;

/** Worker-side progress reporter scoped by the Driver to the claimed task. */
@FunctionalInterface
public interface ModelSummaryProgressReporter {

    ModelSummaryProgressReporter NOOP = (stage, details) -> { };

    void report(String stage, Map<String, Object> details);
}
