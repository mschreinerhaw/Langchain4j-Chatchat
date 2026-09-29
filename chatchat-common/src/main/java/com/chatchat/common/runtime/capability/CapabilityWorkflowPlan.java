package com.chatchat.common.runtime.capability;

import com.chatchat.common.runtime.analysis.model.RuntimeWorkflowFamily;
import java.util.List;
import java.util.Set;

/** Provider-neutral requirements. Resolving a provider never grants access to its resources. */
public record CapabilityWorkflowPlan(RuntimeWorkflowFamily family, List<Requirement> requirements) {
    public static final List<String> LIFECYCLE = List.of("UNDERSTAND", "PROBLEM_ANALYSIS_PLAN", "SELECT_WORKFLOW", "PLAN", "RESOLVE_CAPABILITY", "EXECUTE", "EVALUATE", "COMPLETE");
    public CapabilityWorkflowPlan { requirements = List.copyOf(requirements); }
    public record Requirement(String capability, boolean required, List<String> dependsOn) {
        public Requirement { dependsOn = List.copyOf(dependsOn); }
    }
    public Set<String> requiredCapabilities() {
        return requirements.stream().filter(Requirement::required).map(Requirement::capability)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public static CapabilityWorkflowPlan forFamily(RuntimeWorkflowFamily family) {
        return switch (family) {
            case DIRECT_ANSWER -> new CapabilityWorkflowPlan(family, List.of(required("direct_answer")));
            case DOCUMENT -> new CapabilityWorkflowPlan(family, List.of(
                required("document_scope"), required("document_retrieval", "document_scope"),
                required("evidence_verify", "document_retrieval"), optional("domain_guidance")));
            case DATA_ANALYSIS -> new CapabilityWorkflowPlan(family, List.of(
                required("data_acquisition"), required("data_analysis", "data_acquisition"),
                required("evidence_verify", "data_analysis"), optional("domain_guidance")));
            case ASSET_GUIDANCE -> new CapabilityWorkflowPlan(family, List.of(
                required("asset_resolve"), required("asset_metadata_read", "asset_resolve"),
                optional("asset_usage_read"), optional("domain_guidance")));
            case ACTION -> new CapabilityWorkflowPlan(family, List.of(
                required("action_authorization"), required("action_input_validation"),
                required("action_execute", "action_authorization", "action_input_validation"),
                required("action_verify", "action_execute")));
        };
    }
    private static Requirement required(String id, String... dependencies) { return new Requirement(id, true, List.of(dependencies)); }
    private static Requirement optional(String id) { return new Requirement(id, false, List.of()); }
}
