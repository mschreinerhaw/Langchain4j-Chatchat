package com.chatchat.agents.orchestration.analysis.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Typed publication envelope for the final analysis payload.
 *
 * <p>Prompts, analysis context and repair instructions deliberately have no publishable state.
 * Rendering text is payload data; it is never the signal used to decide whether publication is
 * allowed.</p>
 */
public record AnalysisReportContract(
    String schemaVersion,
    ReportType reportType,
    AnalysisStage sourceStage,
    int admittedFactCount,
    int admittedInsightCount,
    int admittedConclusionCount,
    Publishability publishability,
    String renderedText
) {

    public static final String SCHEMA_VERSION = "analysis_report_contract.v1";

    public AnalysisReportContract {
        schemaVersion = SCHEMA_VERSION;
        reportType = reportType == null ? ReportType.INSTRUCTION : reportType;
        sourceStage = sourceStage == null ? AnalysisStage.RUNTIME : sourceStage;
        admittedFactCount = Math.max(0, admittedFactCount);
        admittedInsightCount = Math.max(0, admittedInsightCount);
        admittedConclusionCount = Math.max(0, admittedConclusionCount);
        publishability = publishability == null ? Publishability.NON_PUBLISHABLE : publishability;
        renderedText = renderedText == null ? "" : renderedText.trim();
    }

    public static AnalysisReportContract driverReport(String text, int facts,
                                                       int insights, int conclusions) {
        boolean hasGovernedAnalysis = facts + insights + conclusions > 0
            && !isInternalInstruction(text);
        return new AnalysisReportContract(SCHEMA_VERSION, ReportType.DRIVER_REPORT,
            AnalysisStage.DRIVER, facts, insights, conclusions,
            hasGovernedAnalysis ? Publishability.PUBLISHABLE_REPORT
                : Publishability.NON_PUBLISHABLE, text);
    }

    public static AnalysisReportContract failureReport(String text) {
        return new AnalysisReportContract(SCHEMA_VERSION, ReportType.FAILURE_REPORT,
            AnalysisStage.GOVERNANCE, 0, 0, 0,
            Publishability.PUBLISHABLE_FAILURE_REPORT, text);
    }

    public boolean mayEnterFinalPayload() {
        if (renderedText.isBlank() || isInternalInstruction(renderedText)) return false;
        return (reportType == ReportType.DRIVER_REPORT
                && publishability == Publishability.PUBLISHABLE_REPORT
                && admittedFactCount + admittedInsightCount + admittedConclusionCount > 0)
            || (reportType == ReportType.FAILURE_REPORT
                && publishability == Publishability.PUBLISHABLE_FAILURE_REPORT);
    }

    public static boolean isInternalInstruction(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return normalized.contains("可以并且必须基于现有数据进行分析")
            || normalized.contains("以下工具结果是本次分析的事实基础")
            || normalized.contains("缺失内容将在限制说明中单独列出")
            || normalized.contains("you must analyze the existing data")
            || normalized.contains("the following tool result is the factual basis");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("reportType", reportType.name());
        value.put("sourceStage", sourceStage.name());
        value.put("admittedFactCount", admittedFactCount);
        value.put("admittedInsightCount", admittedInsightCount);
        value.put("admittedConclusionCount", admittedConclusionCount);
        value.put("publishability", publishability.name());
        value.put("renderedText", renderedText);
        return Map.copyOf(value);
    }

    public enum ReportType {
        INSTRUCTION,
        ANALYSIS_CONTEXT,
        WORKER_REPORT,
        REDUCER_REPORT,
        DRIVER_REPORT,
        FAILURE_REPORT,
        EVIDENCE_ATTACHMENT
    }

    public enum AnalysisStage {
        RUNTIME,
        WORKER,
        REDUCER,
        DRIVER,
        GOVERNANCE
    }

    public enum Publishability {
        PUBLISHABLE_REPORT,
        PUBLISHABLE_FAILURE_REPORT,
        NON_PUBLISHABLE
    }
}
