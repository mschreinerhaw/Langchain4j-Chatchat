package com.chatchat.agents.orchestration.analysis.summary;

import java.util.List;
import java.util.Locale;

/**
 * Technical payload classifier for analysis answers.
 *
 * <p>This classifier only distinguishes analysis prose from Runtime/tool protocol envelopes. It
 * never evaluates whether a business conclusion is acceptable; evidence strength and uncertainty
 * remain annotations for human review.</p>
 */
final class AnalysisOutputAdmissionPolicy {

    static final String WITHHELD_MESSAGE =
        "分析模型没有生成可解析的业务分析正文；已有数据和执行轨迹已保留，可直接复用数据重新生成分析。";

    private static final List<String> ENVELOPE_MARKERS = List.of(
        "\"_aggregation\"", "\"_fieldcount\"", "\"_assessmentcapability\"",
        "\"_lossnotice\"", "\"schemaversion\"", "\"runtimemetadata\"",
        "\"toolname\"", "\"datacompleteness\"", "\"sourcemetadata\"",
        "\"payloadtype\"", "\"executionsource\""
    );

    private static final List<String> INTERNAL_INSTRUCTION_MARKERS = List.of(
        "可以并且必须基于现有数据进行分析",
        "以下工具结果是本次分析的事实基础",
        "缺失内容将在限制说明中单独列出",
        "you must analyze the existing data",
        "the following tool result is the factual basis"
    );

    private AnalysisOutputAdmissionPolicy() {
    }

    static Admission admit(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return new Admission(false, "EMPTY_ANALYSIS_OUTPUT");
        }
        if (WITHHELD_MESSAGE.equals(candidate.trim())) {
            return new Admission(false, "WITHHELD_STATUS_NOT_ANALYSIS");
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
        boolean internalInstruction = INTERNAL_INSTRUCTION_MARKERS.stream()
            .map(marker -> marker.toLowerCase(Locale.ROOT))
            .anyMatch(normalized::contains);
        if (executionManifest || serializedEnvelope || reviewProtocol || internalInstruction) {
            return new Admission(false,
                executionManifest ? "EXECUTION_MANIFEST_NOT_ANALYSIS"
                    : reviewProtocol ? "REVIEW_PROTOCOL_NOT_ANALYSIS"
                    : internalInstruction ? "INTERNAL_INSTRUCTION_NOT_ANALYSIS"
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
