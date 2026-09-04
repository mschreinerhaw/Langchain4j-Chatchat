package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonicalizes model-produced InterpretationPlan payloads before domain
 * deserialization. It owns wire aliases and tool-input compatibility only;
 * plan validation and candidate selection remain separate responsibilities.
 */
public final class InterpretationPlanPayloadNormalizer {

    private final ToolRegistry toolRegistry;

    public InterpretationPlanPayloadNormalizer(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public Map<String, Object> normalize(Map<String, Object> payload) {
        Map<String, Object> normalized = new LinkedHashMap<>(payload);
        alias(normalized, "executionPolicy", "execution_policy");

        Map<String, Object> plan = mutableMap(normalized.get("plan"));
        if (!plan.isEmpty()) {
            alias(plan, "edgeContracts", "edge_contracts");
            alias(plan, "dependencyContracts", "dependency_contracts");
            alias(plan, "conditionalEdges", "conditional_edges");
            alias(plan, "branchGroups", "branch_groups");
            Object rawConditionalEdges = plan.get("conditional_edges");
            if (rawConditionalEdges instanceof List<?> edges) {
                List<Object> normalizedEdges = new ArrayList<>();
                for (Object rawEdge : edges) {
                    Map<String, Object> edge = mutableMap(rawEdge);
                    alias(edge, "branchGroupId", "branch_group_id");
                    alias(edge, "defaultEdge", "default_edge");
                    normalizedEdges.add(edge.isEmpty() ? rawEdge : edge);
                }
                plan.put("conditional_edges", normalizedEdges);
            }
            Object rawBranchGroups = plan.get("branch_groups");
            if (rawBranchGroups instanceof List<?> groups) {
                List<Object> normalizedGroups = new ArrayList<>();
                for (Object rawGroup : groups) {
                    Map<String, Object> group = mutableMap(rawGroup);
                    alias(group, "candidateStepIds", "candidate_step_ids");
                    alias(group, "targetStepId", "target_step_id");
                    alias(group, "selectionStrategy", "selection_strategy");
                    normalizedGroups.add(group.isEmpty() ? rawGroup : group);
                }
                plan.put("branch_groups", normalizedGroups);
            }
            Object rawDependencyContracts = plan.get("dependency_contracts");
            if (rawDependencyContracts instanceof List<?> contracts) {
                List<Object> normalizedContracts = new ArrayList<>();
                for (Object rawContract : contracts) {
                    Map<String, Object> contract = mutableMap(rawContract);
                    if (contract.isEmpty()) {
                        normalizedContracts.add(rawContract);
                        continue;
                    }
                    alias(contract, "onFailure", "on_failure");
                    normalizedContracts.add(contract);
                }
                plan.put("dependency_contracts", normalizedContracts);
            }
            Object rawSteps = plan.get("steps");
            if (rawSteps instanceof List<?> steps) {
                List<Object> normalizedSteps = new ArrayList<>();
                for (Object rawStep : steps) {
                    Map<String, Object> step = mutableMap(rawStep);
                    if (step.isEmpty()) {
                        normalizedSteps.add(rawStep);
                        continue;
                    }
                    alias(step, "actionType", "action_type");
                    alias(step, "toolName", "tool_name");
                    alias(step, "dependsOn", "depends_on");
                    alias(step, "outputContract", "output_contract");
                    Map<String, Object> outputContract = mutableMap(step.get("output_contract"));
                    if (!outputContract.isEmpty()) {
                        alias(outputContract, "schemaHint", "schema_hint");
                        step.put("output_contract", outputContract);
                    }
                    step.put("input", normalizeStepInput(stringValue(step.get("tool_name")), step.get("input")));
                    normalizedSteps.add(step);
                }
                plan.put("steps", normalizedSteps);
            }
            Map<String, Object> stability = mutableMap(plan.get("stability"));
            if (!stability.isEmpty()) {
                alias(stability, "stableNodes", "stable_nodes");
                alias(stability, "criticalTools", "critical_tools");
                alias(stability, "lockedEdges", "locked_edges");
                alias(stability, "mutableActionTypes", "mutable_action_types");
                plan.put("stability", stability);
            }
            normalized.put("plan", plan);
        }

        Map<String, Object> policy = mutableMap(normalized.get("execution_policy"));
        if (!policy.isEmpty()) {
            alias(policy, "maxSteps", "max_steps");
            alias(policy, "allowParallel", "allow_parallel");
            alias(policy, "allowTool", "allow_tool");
            alias(policy, "denyTool", "deny_tool");
            alias(policy, "timeoutMs", "timeout_ms");
            alias(policy, "maxRewriteTimes", "max_rewrite_times");
            alias(policy, "fallbackMode", "fallback_mode");
            alias(policy, "toolPriority", "tool_priority");
            alias(policy, "costBudget", "cost_budget");
            alias(policy, "latencyBudgetMs", "latency_budget_ms");
            alias(policy, "accuracyVsSpeed", "accuracy_vs_speed");
            policy.put("tool_priority", clampPriorityMap(policy.get("tool_priority")));
            policy.put("accuracy_vs_speed", clampNullableDouble(policy.get("accuracy_vs_speed"), 0.0, 1.0));
            normalized.put("execution_policy", policy);
        }

        Map<String, Object> intent = mutableMap(normalized.get("intent"));
        if (!intent.isEmpty()) {
            alias(intent, "riskLevel", "risk_level");
            normalized.put("intent", intent);
        }

        Map<String, Object> context = mutableMap(normalized.get("context"));
        if (!context.isEmpty()) {
            alias(context, "keyFacts", "key_facts");
            alias(context, "missingInfo", "missing_info");
            normalized.put("context", context);
        }

        Map<String, Object> review = mutableMap(normalized.get("review"));
        if (!review.isEmpty()) {
            alias(review, "selfCheck", "self_check");
            Map<String, Object> selfCheck = mutableMap(review.get("self_check"));
            if (!selfCheck.isEmpty()) {
                alias(selfCheck, "completenessScore", "completeness_score");
                alias(selfCheck, "hallucinationRisk", "hallucination_risk");
                alias(selfCheck, "toolSufficiency", "tool_sufficiency");
                alias(selfCheck, "missingSteps", "missing_steps");
                review.put("self_check", selfCheck);
            }
            alias(review, "fallbackPlan", "fallback_plan");
            normalized.put("review", review);
        }
        return normalized;
    }

    private Map<String, Object> normalizeStepInput(String toolName, Object rawInput) {
        Map<String, Object> input = mutableMap(rawInput);
        if (input.isEmpty()) {
            return input;
        }
        String semanticTool = toolSemanticKey(toolName);
        if (workflowRole(toolName) == ToolWorkflowRole.ASSET_DISCOVERY) {
            normalizeDiscoveryQueryInput(input);
        }
        if (workflowRole(toolName) == ToolWorkflowRole.TEMPLATE_DISCOVERY) {
            normalizeDiscoveryQueryInput(input);
        }
        if ("linux_command_execute".equals(semanticTool)) {
            alias(input, "command_template", "template");
            alias(input, "commandTemplate", "template");
            alias(input, "templateCode", "template");
            alias(input, "context", "executionContext");
        }
        return input;
    }

    private void normalizeDiscoveryQueryInput(Map<String, Object> input) {
        Object context = input.remove("context");
        if (context instanceof Map<?, ?> map) {
            input.putIfAbsent("filters", map);
            return;
        }
        if (context != null && !String.valueOf(context).isBlank()) {
            String text = String.valueOf(context).trim();
            Map<String, Object> filters = mutableMap(input.get("filters"));
            if (looksLikeAssetName(text)) {
                filters.putIfAbsent("assetName", text);
            } else {
                filters.putIfAbsent("service", text);
            }
            input.put("filters", filters);
        }
    }

    private boolean looksLikeAssetName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim();
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("_")
            || normalized.contains(":")
            || normalized.startsWith("ssh_")
            || normalized.startsWith("sql_")
            || normalized.startsWith("http_");
    }

    private Map<String, Double> clampPriorityMap(Object value) {
        Map<String, Object> raw = mutableMap(value);
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> clamped = new LinkedHashMap<>();
        raw.forEach((tool, priority) -> {
            Double number = doubleValue(priority);
            if (tool != null && !tool.isBlank() && number != null) {
                clamped.put(tool, clamp(number, 0.0, 1.0));
            }
        });
        return clamped;
    }

    private Double clampNullableDouble(Object value, double min, double max) {
        Double number = doubleValue(value);
        return number == null ? null : clamp(number, min, max);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void alias(Map<String, Object> values, String alias, String canonical) {
        if (values == null || !values.containsKey(alias) || values.containsKey(canonical)) {
            return;
        }
        values.put(canonical, values.remove(alias));
    }

    private Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private String toolSemanticKey(String toolName) {
        if (toolName == null) {
            return "";
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        while (normalized.startsWith("mcp_")) {
            normalized = normalized.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    private ToolWorkflowRole workflowRole(String toolName) {
        if (toolRegistry != null) {
            ToolWorkflowRole role = toolRegistry.getWorkflowRole(toolName);
            if (role != null) {
                return role;
            }
            return ToolWorkflowContract.resolveRole(toolName, toolRegistry.getToolMetadata(toolName));
        }
        return ToolWorkflowContract.resolveRole(toolName, null);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
