package com.chatchat.agents.orchestration.analysis.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Typed provenance envelope for the final analysis payload.
 *
 * <p>Prompts, analysis context and repair instructions deliberately have no publishable state.
 * Rendering text is payload data. Evidence counts and governance observations describe the
 * report for human review; they never authorize the runtime to suppress a business analysis.</p>
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
        boolean hasAnalysisNarrative = text != null && !text.isBlank()
            && !isInternalOrTechnicalPayload(text);
        return new AnalysisReportContract(SCHEMA_VERSION, ReportType.DRIVER_REPORT,
            AnalysisStage.DRIVER, facts, insights, conclusions,
            hasAnalysisNarrative ? Publishability.PUBLISHABLE_REPORT
                : Publishability.NON_PUBLISHABLE, text);
    }

    public static AnalysisReportContract failureReport(String text) {
        return new AnalysisReportContract(SCHEMA_VERSION, ReportType.FAILURE_REPORT,
            AnalysisStage.GOVERNANCE, 0, 0, 0,
            Publishability.PUBLISHABLE_FAILURE_REPORT, text);
    }

    public boolean mayEnterFinalPayload() {
        if (renderedText.isBlank() || isInternalOrTechnicalPayload(renderedText)) return false;
        return (reportType == ReportType.DRIVER_REPORT
                && publishability == Publishability.PUBLISHABLE_REPORT)
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

    public static boolean isInternalOrTechnicalPayload(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (isInternalInstruction(normalized)) return true;
        boolean reviewProtocol = normalized.startsWith("{")
            && normalized.contains("\"accepted\"")
            && normalized.contains("\"feedback\"")
            && normalized.contains("\"revisedanswer\"");
        boolean executionManifest = normalized.contains("## 可用执行结果")
            && normalized.contains("成功子项：")
            && normalized.contains("返回内容：");
        long envelopeMarkers = java.util.stream.Stream.of(
                "\"schemaversion\"", "\"runtimemetadata\"", "\"toolname\"",
                "\"datacompleteness\"", "\"payloadtype\"", "\"executionsource\"")
            .filter(normalized::contains).count();
        return reviewProtocol || executionManifest || envelopeMarkers >= 4;
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
