package com.chatchat.common.runtime.summary.analysis.model;

import com.chatchat.common.runtime.summary.model.ModelSummary;

import java.util.Map;

/**
 * Common-layer model of a governed data-analysis summary.
 *
 * <p>The contract represents chunk, dataset, relationship and final synthesis products without
 * depending on an Agent implementation, model SDK, persistence technology or transport.</p>
 */
public interface DataAnalysisSummary extends ModelSummary {

    String schemaVersion();

    String scope();

    DataAnalysisIsolationScope isolationScope();

    Map<String, Object> position();

    Map<String, Object> analysisContext();

    Map<String, Object> coverage();

    Map<String, Object> governance();
}
