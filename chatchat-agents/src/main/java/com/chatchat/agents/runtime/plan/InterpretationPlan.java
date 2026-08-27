package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured planner output used by the MCP runtime before any tool is executed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InterpretationPlan(
    String version,
    Intent intent,
    Context context,
    Plan plan,
    @JsonProperty("execution_policy")
    ExecutionPolicy executionPolicy,
    Review review
) {

    public InterpretationPlan {
        // Every nested collection component is defensively copied by its owning record.
        // The parsed plan is therefore a value snapshot rather than a mutable JSON view.
    }

    @JsonIgnore
    public List<Step> steps() {
        return plan == null || plan.steps() == null ? List.of() : plan.steps();
    }

    public record Intent(
        String type,
        String goal,
        @JsonProperty("risk_level")
        String riskLevel
    ) {
    }

    public record Context(
        @JsonProperty("key_facts")
        List<String> keyFacts,
        List<String> assumptions,
        @JsonProperty("missing_info")
        List<String> missingInfo,
        List<String> constraints
    ) {
        public Context {
            keyFacts = immutableList(keyFacts);
            assumptions = immutableList(assumptions);
            missingInfo = immutableList(missingInfo);
            constraints = immutableList(constraints);
        }
    }

    public record Plan(
        List<Step> steps,
        @JsonProperty("edge_contracts")
        List<EdgeContract> edgeContracts,
        @JsonProperty("dependency_contracts")
        List<DependencyContract> dependencyContracts,
        List<Binding> bindings,
        Stability stability,
        @JsonProperty("diagnostic_profile")
        DiagnosticProfile diagnosticProfile,
        @JsonProperty("conditional_edges")
        List<ConditionalEdge> conditionalEdges,
        @JsonProperty("branch_groups")
        List<BranchGroup> branchGroups
    ) {
        public Plan {
            steps = immutableList(steps);
            edgeContracts = immutableList(edgeContracts);
            dependencyContracts = immutableList(dependencyContracts);
            bindings = immutableList(bindings);
            conditionalEdges = immutableList(conditionalEdges);
            branchGroups = immutableList(branchGroups);
        }

        public Plan(List<Step> steps) {
            this(steps, List.of(), List.of(), List.of(), null, null, List.of(), List.of());
        }

        public Plan(List<Step> steps, List<EdgeContract> edgeContracts) {
            this(steps, edgeContracts, List.of(), List.of(), null, null, List.of(), List.of());
        }

        public Plan(List<Step> steps, List<EdgeContract> edgeContracts, Stability stability) {
            this(steps, edgeContracts, List.of(), List.of(), stability, null, List.of(), List.of());
        }

        public Plan(List<Step> steps, List<EdgeContract> edgeContracts, List<Binding> bindings, Stability stability) {
            this(steps, edgeContracts, List.of(), bindings, stability, null, List.of(), List.of());
        }

        public Plan(List<Step> steps,
                    List<EdgeContract> edgeContracts,
                    List<DependencyContract> dependencyContracts,
                    List<Binding> bindings,
                    Stability stability) {
            this(steps, edgeContracts, dependencyContracts, bindings, stability, null, List.of(), List.of());
        }

        public Plan(List<Step> steps,
                    List<EdgeContract> edgeContracts,
                    List<DependencyContract> dependencyContracts,
                    List<Binding> bindings,
                    Stability stability,
                    DiagnosticProfile diagnosticProfile) {
            this(steps, edgeContracts, dependencyContracts, bindings, stability, diagnosticProfile,
                List.of(), List.of());
        }
    }

    public record DiagnosticProfile(
        @JsonProperty("profile_id")
        String profileId,
        @JsonProperty("target_kind")
        String targetKind,
        List<DiagnosticCheck> checks,
        @JsonProperty("completion_policy")
        DiagnosticCompletionPolicy completionPolicy
    ) {
        public DiagnosticProfile {
            checks = immutableList(checks);
        }

        public DiagnosticProfile(String profileId, String targetKind, List<DiagnosticCheck> checks) {
            this(profileId, targetKind, checks, null);
        }
    }

    public record DiagnosticCheck(
        @JsonProperty("check_id")
        String checkId,
        String capability,
        String dimension,
        Boolean required,
        Integer priority,
        @JsonProperty("step_ids")
        List<Integer> stepIds,
        Double weight
    ) {
        public DiagnosticCheck {
            stepIds = immutableList(stepIds);
        }

        public DiagnosticCheck(String checkId,
                               String capability,
                               String dimension,
                               Boolean required,
                               Integer priority,
                               List<Integer> stepIds) {
            this(checkId, capability, dimension, required, priority, stepIds, null);
        }
    }

    public record DiagnosticCompletionPolicy(
        @JsonProperty("retry_budget")
        Integer retryBudget,
        @JsonProperty("max_attempts")
        Integer maxAttempts,
        @JsonProperty("high_confidence_threshold")
        Double highConfidenceThreshold,
        @JsonProperty("partial_evidence_threshold")
        Double partialEvidenceThreshold
    ) {
    }

    public record Step(
        Integer id,
        @JsonProperty("action_type")
        String actionType,
        @JsonProperty("tool_name")
        String toolName,
        Map<String, Object> input,
        @JsonProperty("depends_on")
        List<Integer> dependsOn,
        @JsonProperty("output_contract")
        OutputContract outputContract,
        Validation validation
    ) {
        public Step {
            input = immutableMap(input);
            dependsOn = immutableList(dependsOn);
        }

        @JsonIgnore
        public boolean mcpToolAction() {
            return "mcp_tool".equals(actionType);
        }

        @JsonIgnore
        public boolean finalAnswerAction() {
            return "final_answer".equals(actionType);
        }
    }

    public record OutputContract(
        String type,
        @JsonProperty("schema_hint")
        String schemaHint
    ) {
    }

    public record Validation(
        Boolean required,
        String rule,
        Double threshold
    ) {
    }

    public record ExecutionPolicy(
        @JsonProperty("max_steps")
        Integer maxSteps,
        @JsonProperty("allow_parallel")
        Boolean allowParallel,
        @JsonProperty("allow_tool")
        List<String> allowTool,
        @JsonProperty("deny_tool")
        List<String> denyTool,
        @JsonProperty("timeout_ms")
        Integer timeoutMs,
        @JsonProperty("max_rewrite_times")
        Integer maxRewriteTimes,
        @JsonProperty("fallback_mode")
        String fallbackMode,
        @JsonProperty("tool_priority")
        Map<String, Double> toolPriority,
        @JsonProperty("cost_budget")
        Double costBudget,
        @JsonProperty("latency_budget_ms")
        Integer latencyBudgetMs,
        @JsonProperty("accuracy_vs_speed")
        Double accuracyVsSpeed
    ) {
        public ExecutionPolicy {
            allowTool = immutableList(allowTool);
            denyTool = immutableList(denyTool);
            toolPriority = immutableMap(toolPriority);
        }

        public ExecutionPolicy(Integer maxSteps,
                               Boolean allowParallel,
                               List<String> allowTool,
                               List<String> denyTool,
                               Integer timeoutMs) {
            this(maxSteps, allowParallel, allowTool, denyTool, timeoutMs, null, null, null, null, null, null);
        }

        public ExecutionPolicy(Integer maxSteps,
                               Boolean allowParallel,
                               List<String> allowTool,
                               List<String> denyTool,
                               Integer timeoutMs,
                               Integer maxRewriteTimes,
                               String fallbackMode) {
            this(maxSteps, allowParallel, allowTool, denyTool, timeoutMs, maxRewriteTimes, fallbackMode, null, null, null, null);
        }
    }

    public record EdgeContract(
        Integer from,
        Integer to,
        String field,
        String type,
        Boolean required
    ) {
    }

    public record DependencyContract(
        Integer from,
        Integer to,
        Boolean required,
        String condition,
        String reason,
        @JsonProperty("on_failure")
        String onFailure
    ) {
    }

    /** A first-class conditional route. Conditions are semantic predicates, not executable code. */
    public record ConditionalEdge(
        Integer from,
        Integer to,
        @JsonProperty("branch_group_id")
        String branchGroupId,
        String condition,
        Integer priority,
        @JsonProperty("default_edge")
        Boolean defaultEdge
    ) {
    }

    /** Mutually exclusive candidate nodes converging on one downstream target. */
    public record BranchGroup(
        String id,
        @JsonProperty("candidate_step_ids")
        List<Integer> candidateStepIds,
        @JsonProperty("target_step_id")
        Integer targetStepId,
        String mode,
        @JsonProperty("selection_strategy")
        String selectionStrategy
    ) {
        public BranchGroup {
            candidateStepIds = immutableList(candidateStepIds);
        }
    }

    public record Binding(
        Integer from,
        @JsonProperty("output_path")
        String outputPath,
        Integer to,
        @JsonAlias({"input_path", "inputPath"})
        @JsonProperty("input_field")
        String inputField,
        String type,
        Boolean required
    ) {
    }

    public record Stability(
        @JsonProperty("stable_nodes")
        List<Integer> stableNodes,
        @JsonProperty("critical_tools")
        List<String> criticalTools,
        @JsonProperty("locked_edges")
        Boolean lockedEdges,
        @JsonProperty("mutable_action_types")
        List<String> mutableActionTypes
    ) {
        public Stability {
            stableNodes = immutableList(stableNodes);
            criticalTools = immutableList(criticalTools);
            mutableActionTypes = immutableList(mutableActionTypes);
        }
    }

    public record Review(
        @JsonProperty("self_check")
        SelfCheck selfCheck,
        @JsonProperty("fallback_plan")
        List<String> fallbackPlan
    ) {
        public Review {
            fallbackPlan = immutableList(fallbackPlan);
        }
    }

    public record SelfCheck(
        @JsonProperty("completeness_score")
        Double completenessScore,
        @JsonProperty("hallucination_risk")
        Double hallucinationRisk,
        @JsonProperty("tool_sufficiency")
        Boolean toolSufficiency,
        @JsonProperty("missing_steps")
        List<String> missingSteps
    ) {
        public SelfCheck {
            missingSteps = immutableList(missingSteps);
        }
    }

    private static <T> List<T> immutableList(List<T> source) {
        return source == null ? null : Collections.unmodifiableList(new ArrayList<>(source));
    }

    @SuppressWarnings("unchecked")
    static <T> Map<String, T> immutableMap(Map<String, T> source) {
        if (source == null) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return (Map<String, T>) (Map<?, ?>) Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InterpretationPlan::immutableValue).toList();
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableSet(copy);
        }
        return value;
    }
}
