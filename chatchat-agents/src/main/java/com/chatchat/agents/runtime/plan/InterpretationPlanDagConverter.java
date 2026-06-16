package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InterpretationPlanDagConverter {

    public Map<String, Object> convert(InterpretationPlan plan) {
        Map<String, Object> dag = new LinkedHashMap<>();
        if (plan == null) {
            dag.put("nodes", List.of());
            dag.put("edges", List.of());
            return dag;
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step == null || step.id() == null) {
                continue;
            }
            nodes.add(node(step));
            if (step.dependsOn() != null) {
                for (Integer source : step.dependsOn()) {
                    addEdge(edges, edgeIds, source, step.id(), "depends_on", null, null, null);
                }
            }
        }
        if (plan.plan() != null && plan.plan().edgeContracts() != null) {
            for (InterpretationPlan.EdgeContract contract : plan.plan().edgeContracts()) {
                if (contract == null || contract.from() == null || contract.to() == null) {
                    continue;
                }
                addEdge(
                    edges,
                    edgeIds,
                    contract.from(),
                    contract.to(),
                    "contract",
                    contract.field(),
                    contract.type(),
                    contract.required()
                );
            }
        }
        if (plan.plan() != null && plan.plan().bindings() != null) {
            for (InterpretationPlan.Binding binding : plan.plan().bindings()) {
                if (binding == null || binding.from() == null || binding.to() == null) {
                    continue;
                }
                addEdge(
                    edges,
                    edgeIds,
                    binding.from(),
                    binding.to(),
                    "binding",
                    binding.outputPath() + " -> " + binding.inputField(),
                    binding.type(),
                    binding.required()
                );
            }
        }
        dag.put("nodes", nodes);
        dag.put("edges", edges);
        dag.put("summary", summary(plan, nodes.size(), edges.size()));
        return dag;
    }

    public Map<String, Object> convert(InterpretationPlan plan,
                                       String stage,
                                       InterpretationPlanRuntime.ExecutionResult result) {
        Map<String, Object> dag = convert(plan);
        if (result == null) {
            return dag;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dag.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) dag.get("edges");
        Set<String> edgeIds = new LinkedHashSet<>();
        for (Map<String, Object> edge : edges) {
            Object id = edge.get("id");
            if (id != null) {
                edgeIds.add(String.valueOf(id));
            }
        }

        String normalizedStage = stage == null || stage.isBlank() ? "execution" : stage.trim();
        String stageNodeId = "stage-" + normalizedStage;
        Map<String, Object> stageNode = new LinkedHashMap<>();
        stageNode.put("id", stageNodeId);
        stageNode.put("label", normalizedStage + " execution");
        stageNode.put("actionType", "execution_stage");
        stageNode.put("kind", "runtime");
        stageNode.put("status", "running");
        stageNode.put("durationMs", result.durationMs());
        nodes.add(stageNode);

        String previousExecutionNode = stageNodeId;
        int index = 0;
        for (InterpretationPlanRuntime.StepExecution execution : result.steps()) {
            if (execution == null || execution.stepId() == null) {
                continue;
            }
            String executionNodeId = "execution-" + normalizedStage + "-" + execution.stepId() + "-" + (++index);
            Map<String, Object> executionNode = new LinkedHashMap<>();
            executionNode.put("id", executionNodeId);
            executionNode.put("stepId", execution.stepId());
            executionNode.put("label", "run #" + execution.stepId());
            executionNode.put("actionType", "runtime_execution");
            executionNode.put("kind", "runtime");
            executionNode.put("toolName", execution.toolName());
            executionNode.put("status", execution.success() ? "success" : "failed");
            executionNode.put("success", execution.success());
            executionNode.put("durationMs", execution.durationMs());
            executionNode.put("errorMessage", execution.errorMessage());
            executionNode.put("outputPreview", preview(execution.output(), 260));
            nodes.add(executionNode);
            addEdge(edges, edgeIds, previousExecutionNode, executionNodeId, "next", "next", null, null);
            addEdge(edges, edgeIds, "step-" + execution.stepId(), executionNodeId, "executes", "executes", null, null);
            previousExecutionNode = executionNodeId;
        }

        String resultNodeId = "result-" + normalizedStage;
        Map<String, Object> resultNode = new LinkedHashMap<>();
        resultNode.put("id", resultNodeId);
        resultNode.put("label", result.status());
        resultNode.put("actionType", "execution_result");
        resultNode.put("kind", "runtime");
        resultNode.put("status", result.success() ? "success" : "failed");
        resultNode.put("success", result.success());
        resultNode.put("durationMs", result.durationMs());
        resultNode.put("errorMessage", result.errorMessage());
        nodes.add(resultNode);
        addEdge(edges, edgeIds, previousExecutionNode, resultNodeId, "result", "result", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) dag.get("summary");
        summary.put("nodeCount", nodes.size());
        summary.put("edgeCount", edges.size());
        summary.put("executionStatus", result.status());
        summary.put("executionSuccess", result.success());
        summary.put("executionDurationMs", result.durationMs());
        summary.put("executionStepCount", result.steps() == null ? 0 : result.steps().size());
        return dag;
    }

    private Map<String, Object> node(InterpretationPlan.Step step) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "step-" + step.id());
        node.put("stepId", step.id());
        node.put("label", label(step));
        node.put("actionType", step.actionType());
        node.put("toolName", step.toolName());
        node.put("input", step.input() == null ? Map.of() : step.input());
        node.put("dependsOn", step.dependsOn() == null ? List.of() : step.dependsOn());
        node.put("outputContract", step.outputContract());
        node.put("validation", step.validation());
        return node;
    }

    private String label(InterpretationPlan.Step step) {
        String toolName = step.toolName() == null || step.toolName().isBlank() ? "" : step.toolName().trim();
        String actionType = step.actionType() == null || step.actionType().isBlank() ? "step" : step.actionType().trim();
        if (!toolName.isBlank()) {
            return step.id() + ". " + toolName;
        }
        return step.id() + ". " + actionType;
    }

    private void addEdge(List<Map<String, Object>> edges,
                         Set<String> edgeIds,
                         Integer from,
                         Integer to,
                         String kind,
                         String label,
                         String type,
                         Boolean required) {
        String id = "step-" + from + "->step-" + to + ":" + kind + ":" + String.valueOf(label);
        if (!edgeIds.add(id)) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", "step-" + from);
        edge.put("target", "step-" + to);
        edge.put("fromStepId", from);
        edge.put("toStepId", to);
        edge.put("kind", kind);
        edge.put("label", label);
        edge.put("type", type);
        edge.put("required", required);
        edges.add(edge);
    }

    private void addEdge(List<Map<String, Object>> edges,
                         Set<String> edgeIds,
                         String from,
                         String to,
                         String kind,
                         String label,
                         String type,
                         Boolean required) {
        String id = from + "->" + to + ":" + kind + ":" + String.valueOf(label);
        if (!edgeIds.add(id)) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", from);
        edge.put("target", to);
        edge.put("kind", kind);
        edge.put("label", label);
        edge.put("type", type);
        edge.put("required", required);
        edges.add(edge);
    }

    private String preview(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private Map<String, Object> summary(InterpretationPlan plan, int nodeCount, int edgeCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", plan.version());
        summary.put("nodeCount", nodeCount);
        summary.put("edgeCount", edgeCount);
        if (plan.intent() != null) {
            summary.put("intentType", plan.intent().type());
            summary.put("goal", plan.intent().goal());
            summary.put("riskLevel", plan.intent().riskLevel());
        }
        if (plan.executionPolicy() != null) {
            summary.put("maxSteps", plan.executionPolicy().maxSteps());
            summary.put("allowParallel", plan.executionPolicy().allowParallel());
            summary.put("timeoutMs", plan.executionPolicy().timeoutMs());
        }
        return summary;
    }
}
