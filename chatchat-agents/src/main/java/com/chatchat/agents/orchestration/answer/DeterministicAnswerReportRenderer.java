package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders deterministic reports from successful structured tool evidence.
 * This component never selects or reviews an answer; it only presents already
 * governed runtime facts when model synthesis is unavailable.
 */
final class DeterministicAnswerReportRenderer {

    private final AgentResultPresentationService presentationService;

    DeterministicAnswerReportRenderer(ObjectMapper objectMapper) {
        this.presentationService = new AgentResultPresentationService(objectMapper);
    }

    String deterministicBatchReport(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "";
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || trace.getRuntimeMetadata() == null
                || !Boolean.TRUE.equals(trace.getRuntimeMetadata().get("batchExecution"))) {
                continue;
            }
            Map<String, Object> batch = parseObject(trace.getOutput());
            Object rawResults = batch.get("results");
            if (!(rawResults instanceof List<?> results) || results.isEmpty()) {
                continue;
            }
            String status = firstNonBlank(stringValue(batch.get("status")), "NO_PRESENTABLE_RESULT");
            StringBuilder report = new StringBuilder();
            report.append("# MCP 批量诊断执行结果\n\n");
            report.append("- 批次状态：`").append(status).append("`\n");
            report.append("- 执行模式：`")
                .append(firstNonBlank(stringValue(batch.get("executionMode")), "SEQUENTIAL"))
                .append("`\n");
            report.append("- 已记录调用：").append(results.size()).append(" 项\n\n");
            report.append("## 执行清单\n\n");
            for (Object item : results) {
                if (!(item instanceof Map<?, ?> rawItem)) {
                    continue;
                }
                Map<String, Object> result = copyMap(rawItem);
                String callId = firstNonBlank(stringValue(result.get("callId")), "unknown_call");
                String toolName = firstNonBlank(stringValue(result.get("toolName")), "unknown_tool");
                String callStatus = firstNonBlank(stringValue(result.get("status")), "UNKNOWN");
                report.append("- `").append(escapeInline(callId)).append("` / `")
                    .append(escapeInline(toolName)).append("`：")
                    .append(callStatus);
                String templateCode = stringValue(result.get("templateCode"));
                if (templateCode != null && !templateCode.isBlank()) {
                    report.append("，模板 `").append(escapeInline(templateCode)).append("`");
                }
                Object error = result.get("error");
                if (error instanceof Map<?, ?> errorMap && !errorMap.isEmpty()) {
                    String message = stringValue(errorMap.get("message"));
                    if (message != null && !message.isBlank()) {
                        report.append("，原因：").append(escapeInline(shortText(message, 240)));
                    }
                }
                report.append("\n");
            }
            report.append("\n");
            if (DiagnosticRunStateMachine.FailureCode.MODEL_BUDGET_EXHAUSTED.wireValue().equals(status)) {
                report.append("最终分析模型预算已耗尽；以上为运行时根据已持久化工具证据生成的确定性清单。");
            } else if (DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue().equals(status)) {
                report.append("诊断总时间预算已耗尽；已完成的工具结果和失败记录均已保留。");
            } else {
                report.append("最终模型未生成可用总结；以上为运行时根据已持久化工具证据生成的确定性清单。");
            }
            return report.toString().trim();
        }
        return "";
    }

    String deterministicEnterpriseMetadataReport(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "";
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || !trace.isSuccess()) {
                continue;
            }
            Map<String, Object> payload = findEnterpriseMetadataPayload(parseObject(trace.getOutput()), 0);
            if (payload.isEmpty()) {
                continue;
            }
            Map<String, Object> target = mapValue(payload.get("targetObject"));
            Map<String, Object> sourceSchema = mapValue(payload.get("sourceSchema"));
            List<Map<String, Object>> fieldMatches = mapValues(payload.get("fieldMatches"));
            StringBuilder report = new StringBuilder();
            report.append("## 元数据匹配结果\n\n")
                .append("- 目标对象：`")
                .append(escapeInline(firstNonBlank(stringValue(target.get("name")),
                    stringValue(sourceSchema.get("table")))))
                .append("`\n")
                .append("- 协议：`")
                .append(escapeInline(firstNonBlank(stringValue(payload.get("schemaVersion")),
                    "enterprise_metadata_field_discovery.v1")))
                .append("`\n")
                .append("- 已处理字段：")
                .append(fieldMatches.size())
                .append("\n\n")
                .append("| 字段 | 中文名称 | 数据类型 | 可空 | 推荐标准字段 | 建议 | 主要理由 |\n")
                .append("|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> fieldMatch : fieldMatches) {
                Map<String, Object> input = mapValue(fieldMatch.get("input"));
                Map<String, Object> analysis = mapValue(fieldMatch.get("analysis"));
                Map<String, Object> recommended = mapValue(analysis.get("recommendedField"));
                report.append("| ")
                    .append(escapeTableCell(firstNonBlank(stringValue(input.get("fieldName")), "-")))
                    .append(" | ")
                    .append(escapeTableCell(firstNonBlank(
                        firstNonBlank(stringValue(input.get("fieldCnName")), stringValue(input.get("description"))),
                        "-")))
                    .append(" | ")
                    .append(escapeTableCell(firstNonBlank(stringValue(input.get("dataType")), "-")))
                    .append(" | ")
                    .append(escapeTableCell(firstNonBlank(stringValue(input.get("nullable")), "-")))
                    .append(" | ")
                    .append(escapeTableCell(firstNonBlank(
                        firstNonBlank(stringValue(recommended.get("technicalName")), stringValue(recommended.get("name"))),
                        "-")))
                    .append(" | ")
                    .append(escapeTableCell(firstNonBlank(stringValue(analysis.get("recommendation")), "REVIEW")))
                    .append(" | ")
                    .append(escapeTableCell(joinValues(analysis.get("reason"))))
                    .append(" |\n");
            }
            report.append("\n以上内容由已成功返回的结构化元数据证据确定性生成；完整候选、词根、字典及证据对象仍保留在工具轨迹中。");
            return report.toString();
        }
        return "";
    }

    private Map<String, Object> findEnterpriseMetadataPayload(Map<String, Object> value, int depth) {
        if (value == null || value.isEmpty() || depth > 5) {
            return Map.of();
        }
        if ("enterprise_metadata_field_discovery.v1".equals(stringValue(value.get("schemaVersion")))) {
            return value;
        }
        for (String key : List.of("data", "structuredContent", "structured_content", "payload", "result")) {
            Map<String, Object> found = findEnterpriseMetadataPayload(mapValue(value.get(key)), depth + 1);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return Map.of();
    }

    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? copyMap(map) : Map.of();
    }

    private List<Map<String, Object>> mapValues(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return List.copyOf(result);
    }

    private String joinValues(Object value) {
        if (value instanceof List<?> values) {
            return values.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .reduce((left, right) -> left + "；" + right)
                .orElse("-");
        }
        return firstNonBlank(stringValue(value), "-");
    }

    private Map<String, Object> parseObject(String text) {
        return presentationService.parseObject(text);
    }

    private Map<String, Object> copyMap(Map<?, ?> source) {
        return presentationService.copyMap(source);
    }

    private String escapeInline(String value) {
        return value == null ? "" : value.replace("`", "\\`").replace("\r", " ").replace("\n", " ");
    }

    private String escapeTableCell(Object value) {
        return escapeInline(String.valueOf(value == null ? "" : value)).replace("|", "\\|");
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
