package com.chatchat.agents.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Transitional adapter for tools registered before {@code tool_protocol_driver.v1}.
 *
 * <p>New protocol behavior must be published by tool metadata and must not be added here. This
 * adapter can be removed after all deployed MCP servers publish Protocol Driver contracts.</p>
 */
final class LegacyToolProtocolDriverAdapter {

    Optional<ToolProtocolContractResolver.ResolvedContract> contractFor(String toolName) {
        String semantic = semanticName(toolName);
        if (semantic.equals("sql_query_execute") || semantic.equals("sql_script_execute")) {
            return Optional.of(contract("legacy.sql-template.v1", toolName, sqlPlanner(), sqlRewriter()));
        }
        if (semantic.equals("http_request_execute")) {
            return Optional.of(contract("legacy.http-template.v1", toolName, httpPlanner(), httpRewriter()));
        }
        if (semantic.equals("api_template_execute")) {
            return Optional.of(contract("legacy.api-template.v1", toolName, apiPlanner(), apiRewriter()));
        }
        if (semantic.equals("linux_command_execute") || semantic.equals("ssh_linux_execute")) {
            return Optional.of(contract("legacy.ssh-template.v1", toolName, sshPlanner(), sshRewriter()));
        }
        return Optional.empty();
    }

    private ToolProtocolContractResolver.ResolvedContract contract(String id,
                                                                    String toolName,
                                                                    List<String> planner,
                                                                    List<String> rewriter) {
        return new ToolProtocolContractResolver.ResolvedContract(id, toolName, planner, rewriter);
    }

    private List<String> sqlPlanner() {
        return List.of(
            "Use only a registered template selected by the configured template discovery tool; never invent template ids or raw SQL.",
            "Copy the selected scalar templates[].templateId into template/templateId and place only schema-declared business values under parameters.",
            "Use logical executionContext routing. Asset identity is routing context; schema/database values must come from authoritative metadata or template evidence."
        );
    }

    private List<String> sqlRewriter() {
        return List.of(
            "Remove sql/rawSql/query/statement payloads that bypass template governance and repair through an available configured template discovery path.",
            "Preserve authoritative metadata-location evidence and bind only the scalar template id plus required parameters without usable defaults.",
            "If no discovery path or observed compatible template contract exists, return a partial final answer instead of inventing SQL, schema, database, or template ids."
        );
    }

    private List<String> httpPlanner() {
        return List.of(
            "Execute only an accepted registered endpoint template; copy its scalar templateId and pass only parameterSchema-declared values under parameters.",
            "Never send raw url, uri, method, headers, body, endpointId, host, hostname, or ip fields; routing is logical executionContext."
        );
    }

    private List<String> httpRewriter() {
        return List.of(
            "Remove raw transport and endpoint fields, retain only evidence-backed template parameter overrides, and let authoritative defaults apply.",
            "If discovery rejected a candidate, preserve its rejection reason, exclude that template id, and materially refine the bounded discovery request."
        );
    }

    private List<String> apiPlanner() {
        return List.of(
            "Execute only a template accepted from the configured API template discovery/review flow; pass its scalar templateId and schema-declared parameters.",
            "Use capabilitySpec, outputSchema and dependencySpec for requirement coverage and ordering; candidate discovery alone is not execution evidence."
        );
    }

    private List<String> apiRewriter() {
        return List.of(
            "Retain only evidence-backed schema overrides and omit optional/defaulted values so authoritative template defaults apply.",
            "A semantically rejected or incompatible template must not be retried unchanged; preserve the rejection evidence and reselect from authorized candidates."
        );
    }

    private List<String> sshPlanner() {
        return List.of(
            "Execute only a registered command template authorized for the logical target; copy its scalar templateId into template and pass declared values under parameters.",
            "Never pass command, rawCommand, shell, host, hostname, ip, or hostId; use logical executionContext routing."
        );
    }

    private List<String> sshRewriter() {
        return List.of(
            "Remove raw command and concrete host fields, retain only evidence-backed template parameter overrides, and let authoritative defaults apply.",
            "If no suitable authorized command template is available, report the missing template contract instead of inventing a command."
        );
    }

    private String semanticName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        int marker = normalized.lastIndexOf("_mcp_server_");
        if (marker >= 0 && marker + "_mcp_server_".length() < normalized.length()) {
            return normalized.substring(marker + "_mcp_server_".length());
        }
        if (normalized.startsWith("mcp_") && normalized.indexOf('_', 4) > 0) {
            String[] known = {"sql_query_execute", "sql_script_execute", "http_request_execute",
                "api_template_execute", "linux_command_execute", "ssh_linux_execute"};
            for (String name : known) {
                if (normalized.endsWith("_" + name)) {
                    return name;
                }
            }
        }
        return normalized;
    }
}
