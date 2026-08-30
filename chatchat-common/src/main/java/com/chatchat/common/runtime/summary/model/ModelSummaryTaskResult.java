package com.chatchat.common.runtime.summary.model;

import java.util.Map;

/** Serializable worker result with execution identity and failure information. */
public interface ModelSummaryTaskResult<S extends ModelSummary> {

    String taskId();

    String inputSha256();

    String workerId();

    String status();

    long durationMs();

    int attempt();

    S summary();

    String error();

    Map<String, Object> toMap();
}
