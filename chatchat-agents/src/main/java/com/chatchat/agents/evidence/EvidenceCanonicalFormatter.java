package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Formats normalized tool evidence as the stable evidence contract consumed by final synthesis and review.
 */
public class EvidenceCanonicalFormatter {

    public static final String CONTRACT_VERSION = "evidence_canonical_v1";

    private final EvidenceCitationBinder citationBinder;

    public EvidenceCanonicalFormatter() {
        this(new EvidenceCitationBinder());
    }

    public EvidenceCanonicalFormatter(EvidenceCitationBinder citationBinder) {
        this.citationBinder = citationBinder == null ? new EvidenceCitationBinder() : citationBinder;
    }

    public String formatStore(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        List<EvidenceChunk> usable = chunks.stream()
            .filter(chunk -> chunk != null && chunk.content() != null && !chunk.content().isBlank())
            .toList();
        if (usable.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Canonical evidence store (contractVersion=").append(CONTRACT_VERSION).append("):\n");
        for (int i = 0; i < usable.size(); i++) {
            EvidenceChunk chunk = usable.get(i);
            String evidenceId = "evidence:" + (i + 1);
            String refId = citationBinder.refId(chunk, i + 1);
            String content = normalizedContent(chunk.content());
            builder.append("[CanonicalEvidence ").append(i + 1).append("]\n");
            builder.append("evidenceId: ").append(evidenceId).append('\n');
            builder.append("type: ").append(contentType(content)).append('\n');
            builder.append("sourceRef: ").append(refId).append('\n');
            builder.append("trustLevel: ").append(trustLevel(chunk)).append('\n');
            EvidenceSource source = chunk.source();
            if (source != null) {
                appendLine(builder, "source", source.name());
                appendLine(builder, "fileId", source.fileId());
                appendLine(builder, "section", source.section());
                appendLine(builder, "url", source.url());
            }
            Map<String, Object> citation = chunk.citation();
            Object evidenceGrade = citation == null ? null : citation.get("evidenceGrade");
            if (evidenceGrade != null) {
                appendLine(builder, "evidenceGrade", String.valueOf(evidenceGrade));
            }
            builder.append("rawContent:\n").append(content).append('\n');
            builder.append("normalizedContent:\n").append(content).append('\n');
            if (i < usable.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString().trim();
    }

    public List<Map<String, Object>> toMaps(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            EvidenceChunk chunk = chunks.get(i);
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            EvidenceSource source = chunk.source();
            values.add(new java.util.LinkedHashMap<>(Map.of(
                "evidenceId", "evidence:" + (i + 1),
                "type", contentType(chunk.content()),
                "sourceRef", citationBinder.refId(chunk, i + 1),
                "trustLevel", trustLevel(chunk),
                "source", source == null || source.name() == null ? "" : source.name(),
                "rawContent", normalizedContent(chunk.content()),
                "normalizedContent", normalizedContent(chunk.content())
            )));
        }
        return List.copyOf(values);
    }

    private String contentType(String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        if (normalized.contains("select ") || normalized.contains(" from ")
            || normalized.contains("insert ") || normalized.contains("update ")
            || normalized.contains("delete ") || normalized.contains("create table")
            || normalized.contains("with ")) {
            return "SQL";
        }
        if (normalized.contains("|") && normalized.contains("---")) {
            return "TABLE";
        }
        return "TEXT";
    }

    private String trustLevel(EvidenceChunk chunk) {
        EvidenceGovernance governance = chunk == null ? null : chunk.governance();
        if (governance != null && "BLOCKED".equalsIgnoreCase(governance.policyStatus())) {
            return "blocked";
        }
        Object grade = chunk == null || chunk.citation() == null ? null : chunk.citation().get("evidenceGrade");
        if (grade != null) {
            String value = String.valueOf(grade).trim();
            if ("A".equalsIgnoreCase(value)) {
                return "high";
            }
            if ("B".equalsIgnoreCase(value)) {
                return "medium";
            }
        }
        return "high";
    }

    private String normalizedContent(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value).append('\n');
        }
    }
}
