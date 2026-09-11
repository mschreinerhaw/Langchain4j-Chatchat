package com.chatchat.chat.skills;

import com.chatchat.common.knowledge.KnowledgeRequest;

import java.util.Map;

/** Typed projection of stable Agent Runtime policy values stored in workflowConfig. */
public record AgentRuntimePolicy(int knowledgeTokenBudget, long knowledgeSkillTimeoutMs) {

    /** Allows a cold local document-index read to finish without making knowledge unbounded. */
    public static final long DEFAULT_KNOWLEDGE_SKILL_TIMEOUT_MS = 15_000L;

    public static AgentRuntimePolicy from(Map<String, Object> workflowConfig, int defaultKnowledgeBudget) {
        Map<?, ?> policy = nestedPolicy(workflowConfig);
        int budget = clamp(intValue(policy.get("knowledgeTokenBudget"), defaultKnowledgeBudget),
            1, KnowledgeRequest.HARD_MAX_TOKENS);
        long timeout = clamp(longValue(policy.get("knowledgeSkillTimeoutMs"),
            DEFAULT_KNOWLEDGE_SKILL_TIMEOUT_MS), 100L, 60_000L);
        return new AgentRuntimePolicy(budget, timeout);
    }

    private static Map<?, ?> nestedPolicy(Map<String, Object> workflowConfig) {
        if (workflowConfig == null || workflowConfig.isEmpty()) {
            return Map.of();
        }
        Object nested = workflowConfig.get("runtimePolicy");
        return nested instanceof Map<?, ?> map ? map : workflowConfig;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
