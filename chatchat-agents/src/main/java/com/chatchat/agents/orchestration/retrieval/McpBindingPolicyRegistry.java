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
        PASSTHROUGH, ASSET_DISCOVERY, TEMPLATE_DISCOVERY, SQL_EXECUTION, HTTP_EXECUTION, SHELL_EXECUTION, REJECTED
    }

    private final Map<String, Policy> executionProtocols;

    public McpBindingPolicyRegistry() {
        this(Map.of());
    }

    public McpBindingPolicyRegistry(Map<String, Policy> additionalExecutionProtocols) {
        Map<String, Policy> protocols = new LinkedHashMap<>();
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
        Policy override = executionProtocols.get(protocol);
        if (override != null) return override;
        McpArgumentBindingFieldPolicy fields = McpArgumentBindingFieldPolicy.from(metadata);
        if (fields == null) return Policy.REJECTED;
        try {
            Policy selected = Policy.valueOf(fields.executionProtocolBindings().getOrDefault(protocol, "REJECTED"));
            return selected == Policy.SQL_EXECUTION || selected == Policy.HTTP_EXECUTION
                || selected == Policy.SHELL_EXECUTION ? selected : Policy.REJECTED;
        } catch (IllegalArgumentException invalidMode) {
            return Policy.REJECTED;
        }
    }
}
