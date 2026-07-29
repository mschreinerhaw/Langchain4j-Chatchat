package com.chatchat.agents.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single version entry point for protocols that cross Agent module layers.
 *
 * <p>Domain-local data contracts keep their version next to their owning type. A version belongs
 * here when it is read or written by more than one package (model, orchestration, runtime, routing
 * or tool-call bridge). Callers must reference these constants instead of repeating literals.</p>
 */
public final class AgentProtocolCatalog {

    public static final String CATALOG_VERSION = "agent_protocol_catalog.v1";

    public static final String INTERPRETATION_EXECUTION = "interpretation_execution_protocol_v1";
    public static final String TEMPLATE_PARAMETER = "template_parameter_protocol_v2";
    public static final String RUNTIME_TEMPLATE_BINDING = "runtime_template_binding.v1";
    public static final String RUNTIME_DEPENDENCY_EVIDENCE = "runtime_dependency_evidence.v1";
    public static final String TARGET_FILTERS = "target_filters.v1";
    public static final String ROUTING_TRACE = "routing_trace.v1";
    public static final String RUNTIME_ARGUMENT_RESOLUTION = "runtime_argument_resolution.v1";
    public static final String RUNTIME_ANSWER_CANDIDATE = "runtime_answer_candidate_v1";

    private static final Map<String, ProtocolDescriptor> CURRENT = currentProtocols();

    private AgentProtocolCatalog() {
    }

    public static ProtocolDescriptor protocol(String id) {
        ProtocolDescriptor descriptor = CURRENT.get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown cross-layer Agent protocol: " + id);
        }
        return descriptor;
    }

    public static Map<String, ProtocolDescriptor> current() {
        return CURRENT;
    }

    public static Map<String, Object> trace(String entryPoint,
                                            String templateId,
                                            String executorTool,
                                            boolean modelParametersReviewed) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("catalogVersion", CATALOG_VERSION);
        trace.put("entryPoint", entryPoint == null ? "" : entryPoint);
        trace.put("executionProtocol", INTERPRETATION_EXECUTION);
        trace.put("parameterProtocol", TEMPLATE_PARAMETER);
        trace.put("templateBindingProtocol", RUNTIME_TEMPLATE_BINDING);
        trace.put("templateId", templateId == null ? "" : templateId);
        trace.put("executorTool", executorTool == null ? "" : executorTool);
        trace.put("modelParametersReviewed", modelParametersReviewed);
        return Map.copyOf(trace);
    }

    private static Map<String, ProtocolDescriptor> currentProtocols() {
        Map<String, ProtocolDescriptor> protocols = new LinkedHashMap<>();
        register(protocols, INTERPRETATION_EXECUTION, "runtime.plan",
            "model -> orchestration -> runtime", "DAG controller decision and guard lifecycle");
        register(protocols, TEMPLATE_PARAMETER, "runtime.toolcall",
            "model -> template bridge -> runtime", "Evidence-bearing semantic template arguments");
        register(protocols, RUNTIME_TEMPLATE_BINDING, "runtime.plan",
            "template discovery -> runtime", "Runtime-owned selected template identity");
        register(protocols, RUNTIME_DEPENDENCY_EVIDENCE, "runtime",
            "completed tools -> executor adapter", "Structured predecessor evidence transport");
        register(protocols, TARGET_FILTERS, "routing",
            "orchestration -> discovery tool", "Logical target discovery filters");
        register(protocols, ROUTING_TRACE, "routing",
            "orchestration/runtime -> discovery tool", "Replayable routing decision trace");
        register(protocols, RUNTIME_ARGUMENT_RESOLUTION, "runtime.plan",
            "runtime bindings -> executor", "Resolved argument provenance");
        register(protocols, RUNTIME_ANSWER_CANDIDATE, "assessment",
            "runtime stages -> assessment -> finalizer", "Intermediate answer candidate lifecycle");
        return Map.copyOf(protocols);
    }

    private static void register(Map<String, ProtocolDescriptor> protocols,
                                 String id,
                                 String owner,
                                 String flow,
                                 String purpose) {
        protocols.put(id, new ProtocolDescriptor(id, owner, flow, purpose));
    }

    public record ProtocolDescriptor(String id, String owner, String flow, String purpose) {
    }
}
