package com.chatchat.agents.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultAgentObservationPipeline implements AgentObservationPipeline {

    @Override
    public AgentObservation fromText(String content) {
        String safeContent = content == null ? "" : content;
        return AgentObservation.builder()
            .type(type(safeContent))
            .source(source(safeContent))
            .content(safeContent)
            .metadata(metadata(safeContent))
            .build();
    }

    private String type(String content) {
        if (content == null || content.isBlank()) {
            return "text";
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        if (normalized.contains("contractversion=evidence_v1")
            || normalized.contains("\"contractversion\":\"evidence_v1\"")
            || normalized.contains("unified evidence context")) {
            if (normalized.contains("type: document")) {
                return "document_evidence";
            }
            return "evidence";
        }
        if (normalized.contains("evidence trust policy") || normalized.contains("web citation map")
            || normalized.contains("web search summary")) {
            return "web_evidence";
        }
        if (normalized.contains("document_evidence_v1")
            || normalized.contains("document evidence snippets")
            || normalized.contains("document search")
            || (normalized.contains("\"contractversion\"") && normalized.contains("\"citations\""))) {
            return "document_evidence";
        }
        if (normalized.startsWith("mandatory fallback tool ")
            || normalized.startsWith("confirmed pending tool ")
            || normalized.startsWith("tool ")) {
            return normalized.contains(" failed.") ? "tool_failure" : "tool";
        }
        if (normalized.startsWith("planner ")) {
            return "planner";
        }
        return "text";
    }

    private String source(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String normalized = content.trim();
        String tool = toolSource(normalized, "Mandatory fallback Tool ");
        if (tool != null) {
            return tool;
        }
        tool = toolSource(normalized, "Confirmed pending Tool ");
        if (tool != null) {
            return tool;
        }
        tool = toolSource(normalized, "Tool ");
        if (tool != null) {
            return tool;
        }
        if (normalized.startsWith("Planner ")) {
            return "planner";
        }
        return "agent";
    }

    private Map<String, Object> metadata(String content) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("legacyTextObservation", true);
        values.put("length", content == null ? 0 : content.length());
        if (content != null && content.contains("Evidence trust policy")) {
            values.put("containsTrustPolicy", true);
        }
        if (content != null && (content.contains("contractVersion=evidence_v1") || content.contains("Unified evidence context"))) {
            values.put("containsUnifiedEvidence", true);
            values.put("evidenceContractVersion", "evidence_v1");
        }
        if (content != null && content.contains("Web citation map")) {
            values.put("containsCitations", true);
        }
        if (content != null && (content.contains("document_evidence_v1") || content.contains("\"citations\""))) {
            values.put("containsDocumentCitations", true);
        }
        return values;
    }

    private String toolSource(String text, String prefix) {
        if (text == null || prefix == null || !text.startsWith(prefix)) {
            return null;
        }
        int start = prefix.length();
        int end = text.indexOf(' ', start);
        if (end <= start) {
            return "tool";
        }
        return text.substring(start, end);
    }
}
