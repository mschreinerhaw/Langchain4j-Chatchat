package com.chatchat.agents.orchestration.analysis;

import java.util.Map;

/** Worker-side reporter scoped to the currently claimed dataset task. */
@FunctionalInterface
public interface AnalysisTaskProgressReporter {

    AnalysisTaskProgressReporter NOOP = (stage, details) -> { };

    void report(String stage, Map<String, Object> details);
}
