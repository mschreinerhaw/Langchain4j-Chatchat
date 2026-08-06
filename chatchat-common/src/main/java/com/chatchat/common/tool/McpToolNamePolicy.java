package com.chatchat.common.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared MCP tool-name contract used by publication and workflow review.
 */
public final class McpToolNamePolicy {

    public static final int MAX_NAME_LENGTH = 128;
    private static final Pattern WIRE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final String[] TRANSPORT_PREFIXES = {
        "chatchat_mcp_server_",
        "chatchat_",
        "xxx_"
    };

    private McpToolNamePolicy() {
    }

    /**
     * Returns the same comparison key used by workflow tool-name review.
     */
    public static String workflowSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : TRANSPORT_PREFIXES) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return normalized;
    }

    public static String requirePublishableName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("MCP tool name is required");
        }
        if (!toolName.equals(toolName.trim())) {
            throw new IllegalArgumentException("MCP tool name must not contain leading or trailing whitespace: '"
                + toolName + "'");
        }
        if (toolName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("MCP tool name exceeds " + MAX_NAME_LENGTH + " characters: "
                + toolName);
        }
        if (!WIRE_NAME.matcher(toolName).matches()) {
            throw new IllegalArgumentException("MCP tool name contains unsupported characters: " + toolName);
        }
        if (workflowSemanticKey(toolName).isBlank()) {
            throw new IllegalArgumentException("MCP tool name has no workflow semantic identity: " + toolName);
        }
        return toolName;
    }

    /**
     * Rejects names that are distinct on the MCP wire but ambiguous to workflow review.
     */
    public static void auditPublicationNames(Collection<String> toolNames) {
        Map<String, String> namesBySemanticKey = new LinkedHashMap<>();
        if (toolNames == null) {
            return;
        }
        for (String name : toolNames) {
            requirePublishableName(name);
            String semanticKey = workflowSemanticKey(name);
            String existing = namesBySemanticKey.putIfAbsent(semanticKey, name);
            if (existing != null && !existing.equals(name)) {
                throw new IllegalArgumentException("MCP tool names are ambiguous to workflow review: '"
                    + existing + "' and '" + name + "' resolve to '" + semanticKey + "'");
            }
        }
    }
}
