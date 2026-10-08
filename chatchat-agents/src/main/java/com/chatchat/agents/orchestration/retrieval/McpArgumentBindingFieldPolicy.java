package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.tool.ToolMetadata;

import java.util.List;
import java.util.Map;

/** Field groups supplied by the active database-backed MCP tool contract. */
record McpArgumentBindingFieldPolicy(List<String> logicalContextKeys,
                                     List<String> concreteTargetFields,
                                     List<String> rawExecutionFields,
                                     List<String> targetKindFields,
                                     List<String> filterProtocolFields) {

    static McpArgumentBindingFieldPolicy from(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) return null;
        Map<String, Object> extra = metadata.getMetadata();
        Object value = extra.get("argumentBindingPolicy");
        if (value == null && extra.get("mcpToolMeta") instanceof Map<?, ?> mcpMeta) {
            value = mcpMeta.get("argumentBindingPolicy");
        }
        if (!(value instanceof Map<?, ?> policy)) return null;
        List<String> logical = fields(policy, "logicalContextKeys");
        List<String> concrete = fields(policy, "concreteTargetFields");
        List<String> raw = fields(policy, "rawExecutionFields");
        List<String> target = fields(policy, "targetKindFields");
        List<String> protocol = fields(policy, "filterProtocolFields");
        if (logical == null || concrete == null || raw == null || target == null || protocol == null) return null;
        return new McpArgumentBindingFieldPolicy(logical, concrete, raw, target, protocol);
    }

    private static List<String> fields(Map<?, ?> policy, String key) {
        if (!(policy.get(key) instanceof List<?> raw) || raw.isEmpty()) return null;
        if (raw.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) return null;
        return raw.stream().map(String::valueOf).distinct().toList();
    }
}
