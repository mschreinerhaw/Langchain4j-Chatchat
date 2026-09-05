package com.chatchat.common.runtime.summary.analysis.model;

import java.util.Map;

/**
 * Framework-neutral partition identity carried by governed analysis summaries.
 * Authentication and tenancy implementations remain owned by the upper Runtime layer.
 */
public interface DataAnalysisIsolationScope {

    String schemaVersion();

    String partitionKey();

    boolean samePartition(DataAnalysisIsolationScope other);

    Map<String, Object> toMap();
}
