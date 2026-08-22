package com.chatchat.common.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final Set<String> TEMPLATE_DISCOVERY_BRIDGES = Set.of(
        "api_service_query",
        "server_capability_query",
        "http_capability_query",
        "jmx_capability_query",
        "database_capability_query",
        "data_query_query",
        "python_analysis_query"
    );
    private static final Set<String> TEMPLATE_EXECUTION_TOOLS = Set.of(
        "template_execute",
        "sql_query_execute",
        "sql_script_execute",
        "linux_command_execute",
        "ssh_linux_execute",
        "http_request_execute",
        "api_template_execute",
        "python_template_execute",
        "jmx_monitor_execute"
    );

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

    public static boolean isAssetDiscovery(String toolName) {
        String semantic = workflowSemanticKey(toolName);
        return "asset_discovery".equals(semantic)
            || "asset_query".equals(semantic)
            || semantic.endsWith("_asset_query")
            || "asset_search".equals(semantic)
            || semantic.endsWith("_asset_search")
            || "database_asset_search".equals(semantic);
    }

    public static boolean isTemplateDiscovery(String toolName) {
        String semantic = workflowSemanticKey(toolName);
        return "template_discovery".equals(semantic)
            || "template_query".equals(semantic)
            || semantic.endsWith("_template_query")
            || semantic.endsWith("_template_search")
            || matchesRoleFamily(semantic, TEMPLATE_DISCOVERY_BRIDGES);
    }

    public static boolean isTemplateDiscoveryBridge(String toolName) {
        return matchesRoleFamily(workflowSemanticKey(toolName), TEMPLATE_DISCOVERY_BRIDGES);
    }

    public static boolean isRoutingDiscovery(String toolName) {
        return isAssetDiscovery(toolName) || isTemplateDiscovery(toolName);
    }

    public static boolean isTemplateExecution(String toolName) {
        String semantic = workflowSemanticKey(toolName);
        return "execute".equals(semantic)
            || semantic.endsWith("_template_execute")
            || matchesRoleFamily(semantic, TEMPLATE_EXECUTION_TOOLS);
    }

    private static boolean matchesRoleFamily(String semantic, Set<String> roles) {
        return semantic != null && roles.stream()
            .anyMatch(role -> semantic.equals(role) || semantic.endsWith("_" + role));
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
