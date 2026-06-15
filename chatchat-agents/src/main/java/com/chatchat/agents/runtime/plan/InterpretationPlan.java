package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

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
    }

    public record Plan(
        List<Step> steps,
        @JsonProperty("edge_contracts")
        List<EdgeContract> edgeContracts,
        List<Binding> bindings,
        Stability stability
    ) {
        public Plan(List<Step> steps) {
            this(steps, List.of(), List.of(), null);
        }

        public Plan(List<Step> steps, List<EdgeContract> edgeContracts) {
            this(steps, edgeContracts, List.of(), null);
        }

        public Plan(List<Step> steps, List<EdgeContract> edgeContracts, Stability stability) {
            this(steps, edgeContracts, List.of(), stability);
        }
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

    public record Binding(
        Integer from,
        @JsonProperty("output_path")
        String outputPath,
        Integer to,
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
    }

    public record Review(
        @JsonProperty("self_check")
        SelfCheck selfCheck,
        @JsonProperty("fallback_plan")
        List<String> fallbackPlan
    ) {
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
    }
}
