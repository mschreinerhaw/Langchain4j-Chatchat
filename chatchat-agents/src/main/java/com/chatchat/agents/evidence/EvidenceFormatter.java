package com.chatchat.agents.evidence;

import java.util.List;

public class EvidenceFormatter {

    private final EvidenceCitationBinder citationBinder;

    public EvidenceFormatter() {
        this(new EvidenceCitationBinder());
    }

    public EvidenceFormatter(EvidenceCitationBinder citationBinder) {
        this.citationBinder = citationBinder == null ? new EvidenceCitationBinder() : citationBinder;
    }

    public String formatContext(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Unified evidence context (contractVersion=evidence_v1):\n");
        for (int i = 0; i < chunks.size(); i++) {
            EvidenceChunk chunk = chunks.get(i);
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("[Evidence ").append(i + 1).append("]\n");
            builder.append("type: ").append(chunk.evidenceType()).append('\n');
            builder.append("citation: ").append(citationBinder.refId(chunk, i + 1)).append('\n');
            Object evidenceGrade = chunk.citation() == null ? null : chunk.citation().get("evidenceGrade");
            if (evidenceGrade != null) {
                builder.append("evidenceGrade: ").append(evidenceGrade).append('\n');
            }
            EvidenceSource source = chunk.source();
            if (source != null) {
                appendLine(builder, "source", source.name());
                appendLine(builder, "url", source.url());
                appendLine(builder, "domain", source.domain());
                appendLine(builder, "fileId", source.fileId());
                appendLine(builder, "section", source.section());
            }
            if (chunk.score() != null) {
                builder.append("score: ").append(chunk.score()).append('\n');
            }
            EvidenceGovernance governance = chunk.governance();
            if (governance != null) {
                appendLine(builder, "tenantId", governance.tenantId());
                appendLine(builder, "userId", governance.userId());
                builder.append("policyStatus: ").append(governance.policyStatus()).append('\n');
            }
            builder.append("content:\n").append(chunk.content().trim()).append('\n');
        }
        return builder.toString().trim();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(": ").append(value).append('\n');
        }
    }
}
