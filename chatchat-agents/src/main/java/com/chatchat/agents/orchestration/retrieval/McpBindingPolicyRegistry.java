package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Selects argument binding from the published workflow contract, independent of tool names. */
public final class McpBindingPolicyRegistry {

    public enum Policy {
        PASSTHROUGH, ASSET_DISCOVERY, TEMPLATE_DISCOVERY, SQL_EXECUTION, HTTP_EXECUTION, SHELL_EXECUTION
    }

    private static final Map<String, Policy> STANDARD_EXECUTION_PROTOCOLS = Map.of(
        "mcp.sql-template.v1", Policy.SQL_EXECUTION,
        "mcp.ssh-template.v1", Policy.SHELL_EXECUTION,
        "mcp.http-template.v1", Policy.HTTP_EXECUTION,
        "mcp.api-template.v1", Policy.HTTP_EXECUTION
    );

    private final Map<String, Policy> executionProtocols;

    public McpBindingPolicyRegistry() {
        this(Map.of());
    }

    public McpBindingPolicyRegistry(Map<String, Policy> additionalExecutionProtocols) {
        Map<String, Policy> protocols = new LinkedHashMap<>(STANDARD_EXECUTION_PROTOCOLS);
        additionalExecutionProtocols.forEach((family, policy) ->
            protocols.put(family.trim().toLowerCase(Locale.ROOT), policy));
        this.executionProtocols = Map.copyOf(protocols);
    }

    public Policy resolve(ToolMetadata metadata) {
        ToolWorkflowRole role = ToolWorkflowContract.declaredRole(metadata).orElse(ToolWorkflowRole.DIRECT);
        if (role == ToolWorkflowRole.ASSET_DISCOVERY) return Policy.ASSET_DISCOVERY;
        if (role == ToolWorkflowRole.TEMPLATE_DISCOVERY) return Policy.TEMPLATE_DISCOVERY;
        if (role != ToolWorkflowRole.TEMPLATE_EXECUTION) return Policy.PASSTHROUGH;
        String protocol = ToolWorkflowContract.declaredProtocolFamily(metadata)
            .map(value -> value.trim().toLowerCase(Locale.ROOT)).orElse("");
        return executionProtocols.getOrDefault(protocol, Policy.PASSTHROUGH);
    }
}
