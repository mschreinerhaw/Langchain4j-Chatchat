package com.chatchat.common.runtime.summary.model;

import java.util.Map;

/** Serializable, idempotent unit of model-summary work. */
public interface ModelSummaryTask {

    String schemaVersion();

    String taskId();

    String inputSha256();

    long timeoutMs();

    int attempt();

    default String idempotencyKey() {
        return taskId() + ":" + inputSha256();
    }

    Map<String, Object> toMap();
}
