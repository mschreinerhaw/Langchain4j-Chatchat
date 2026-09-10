package com.chatchat.common.knowledge;

import java.util.List;

/** The only knowledge object consumed by Agent Runtime. */
public record KnowledgeContext(
    String schemaVersion,
    KnowledgeSkillPlan plan,
    List<KnowledgeIR> knowledgeUnits,
    String compiledContext,
    List<KnowledgeSourceReference> sources,
    int estimatedTokens,
    int maxTokens,
    boolean truncated,
    String status
) {
    public static final String SCHEMA_VERSION = "knowledge_context.v1";

    public KnowledgeContext {
        schemaVersion = SCHEMA_VERSION;
        knowledgeUnits = knowledgeUnits == null ? List.of() : List.copyOf(knowledgeUnits);
        compiledContext = compiledContext == null ? "" : compiledContext;
        sources = sources == null ? List.of() : List.copyOf(sources);
        estimatedTokens = Math.max(0, estimatedTokens);
        maxTokens = Math.max(0, maxTokens);
        if (maxTokens > 0 && estimatedTokens > maxTokens) {
            throw new IllegalArgumentException("Compiled knowledge exceeds token budget");
        }
        status = status == null || status.isBlank() ? "empty" : status.trim();
    }

    public boolean used() {
        return !compiledContext.isBlank();
    }

    public static KnowledgeContext empty(String status, int maxTokens) {
        return new KnowledgeContext(SCHEMA_VERSION, null, List.of(), "", List.of(), 0,
            Math.max(0, maxTokens), false, status);
    }
}
