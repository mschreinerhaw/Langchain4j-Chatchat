package com.chatchat.common.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata contract through which a tool publishes model-facing planning and repair guidance.
 *
 * <p>The tool owns transport- and protocol-specific rules. Agent Kernel consumers only select
 * contracts belonging to currently available tools and inject the phase that they need.</p>
 */
public final class ToolProtocolDriverContract {

    public static final String METADATA_KEY = "protocolDriver";
    public static final String SCHEMA_VERSION = "tool_protocol_driver.v1";

    private ToolProtocolDriverContract() {
    }

    public static Map<String, Object> of(String driverId,
                                         List<String> plannerRules,
                                         List<String> rewriterRules) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("schemaVersion", SCHEMA_VERSION);
        contract.put("driverId", driverId);
        contract.put("plannerRules", plannerRules == null ? List.of() : List.copyOf(plannerRules));
        contract.put("rewriterRules", rewriterRules == null ? List.of() : List.copyOf(rewriterRules));
        return Map.copyOf(contract);
    }
}
