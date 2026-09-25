package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;
import java.util.Map;

/** Agent proposes an evidence need; Runtime chooses and authorizes the local Skill/data operation. */
public record AgentToolRequest(String requestId, String type, String evidenceType,
                               int minimumCount, String reason) {
    public static final String TYPE = "SUPPLEMENT_EVIDENCE";

    public AgentToolRequest {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128)
            throw new IllegalArgumentException("tool requestId is required (1..128 chars)");
        if (!TYPE.equals(type)) throw new IllegalArgumentException("Only SUPPLEMENT_EVIDENCE tool requests are supported");
        if (evidenceType == null || !evidenceType.matches("[A-Z][A-Z0-9_]{1,63}"))
            throw new IllegalArgumentException("Invalid evidence type");
        if (minimumCount < 1 || minimumCount > 5)
            throw new IllegalArgumentException("Tool request minimumCount must be 1..5");
        reason = reason == null ? "" : reason.trim();
        if (reason.length() > 500) throw new IllegalArgumentException("Tool request reason exceeds 500 characters");
    }

    public EvidenceRequirement requirement() {
        return new EvidenceRequirement(evidenceType, true, minimumCount, reason);
    }

    public static AgentToolRequest from(Object raw) {
        if (raw instanceof AgentToolRequest request) return request;
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException("tool request must be an object");
        if (!values.keySet().stream().allMatch(key -> key instanceof String text &&
            java.util.Set.of("requestId", "type", "evidenceType", "minimumCount", "reason").contains(text)))
            throw new IllegalArgumentException("tool request contains unsupported fields");
        Object count = values.get("minimumCount");
        if (count != null && !(count instanceof Integer))
            throw new IllegalArgumentException("Tool request minimumCount must be an integer");
        int minimum = count instanceof Integer number ? number : 1;
        return new AgentToolRequest(text(values.get("requestId")), text(values.get("type")),
            text(values.get("evidenceType")), minimum, text(values.get("reason")));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
