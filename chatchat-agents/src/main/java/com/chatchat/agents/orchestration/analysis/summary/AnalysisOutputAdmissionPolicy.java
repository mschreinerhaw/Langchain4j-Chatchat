package com.chatchat.agents.orchestration.analysis.summary;

import java.util.List;
import java.util.Locale;

/**
 * Fail-closed publication policy for analysis answers.
 *
 * <p>The policy is deliberately domain neutral. It recognizes Runtime/tool protocol envelopes,
 * not business fields, and prevents an execution manifest or a serialized evidence payload from
 * being mistaken for a completed analysis.</p>
 */
final class AnalysisOutputAdmissionPolicy {

    static final String WITHHELD_MESSAGE =
        "本轮数据已获取，但分析过程未能完成，因此没有生成可发布的结论。"
            + "请稍后重试；已获取的数据不会被当作分析结论直接展示。";

    private static final List<String> ENVELOPE_MARKERS = List.of(
        "\"_aggregation\"", "\"_fieldcount\"", "\"_assessmentcapability\"",
        "\"_lossnotice\"", "\"schemaversion\"", "\"runtimemetadata\"",
        "\"toolname\"", "\"datacompleteness\"", "\"sourcemetadata\"",
        "\"payloadtype\"", "\"executionsource\""
    );

    private AnalysisOutputAdmissionPolicy() {
    }

    static Admission admit(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return new Admission(false, "EMPTY_ANALYSIS_OUTPUT");
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        long markerCount = ENVELOPE_MARKERS.stream().filter(normalized::contains).count();
        boolean executionManifest = normalized.contains("## 可用执行结果")
            && normalized.contains("成功子项：") && normalized.contains("返回内容：");
        boolean serializedEnvelope = markerCount >= 4
            && (normalized.length() >= 1_000 || normalized.contains("```json")
                || normalized.contains("返回内容：`{"));
        boolean reviewProtocol = normalized.trim().startsWith("{")
            && normalized.contains("\"accepted\"")
            && normalized.contains("\"feedback\"")
            && normalized.contains("\"revisedanswer\"");
        if (executionManifest || serializedEnvelope || reviewProtocol) {
            return new Admission(false,
                executionManifest ? "EXECUTION_MANIFEST_NOT_ANALYSIS"
                    : reviewProtocol ? "REVIEW_PROTOCOL_NOT_ANALYSIS"
                    : "RUNTIME_ENVELOPE_NOT_ANALYSIS");
        }
        return new Admission(true, "ANALYSIS_NARRATIVE_ADMITTED");
    }

    static Admission admitWorkerNarrative(String candidate) {
        Admission publication = admit(candidate);
        if (!publication.admitted()) return publication;
        String normalized = candidate.toLowerCase(Locale.ROOT);
        boolean runtimeProtocol = normalized.contains("tool call batch")
            || normalized.contains("required tool")
            || normalized.contains("technical reason")
            || normalized.contains("必需工具")
            || normalized.contains("未满足必需证据")
            || normalized.contains("执行已结束，但结果整理失败")
            || normalized.contains("工具轨迹")
            || normalized.contains("mcp_tool_error")
            || normalized.contains("circuit_open")
            || normalized.contains("illegalargumentexception");
        if (runtimeProtocol) {
            return new Admission(false, "RUNTIME_PROTOCOL_TEXT_NOT_ANALYSIS");
        }
        return new Admission(true, "LEGACY_ANALYSIS_NARRATIVE_DEGRADED");
    }

    record Admission(boolean admitted, String reason) {
    }
}
