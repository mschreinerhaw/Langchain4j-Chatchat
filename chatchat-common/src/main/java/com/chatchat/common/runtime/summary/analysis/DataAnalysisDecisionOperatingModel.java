package com.chatchat.common.runtime.summary.analysis;

/**
 * Source-neutral operating model for governed data-analysis decisions.
 *
 * <p>Workers analyze assigned evidence, reducers consolidate analysis products, the Driver
 * reviews those products and governs decision direction, and Governance enforces evidence and
 * publication boundaries. No domain vocabulary or business rule belongs in this contract.</p>
 */
public final class DataAnalysisDecisionOperatingModel {

    public static final String SCHEMA_VERSION = "data_analysis_decision_operating_model.v1";
    public static final String WORKER_REPORT_SCHEMA_VERSION = "worker_analysis_report.v1";

    private DataAnalysisDecisionOperatingModel() {
    }

    public enum ParticipantRole {
        WORKER,
        REDUCER,
        DRIVER,
        GOVERNANCE
    }

    public enum DriverInputMode {
        GOVERNED_WORKER_REDUCER_REPORTS_ONLY,
        WORKER_REPORT_COMPATIBILITY_FALLBACK
    }
}
