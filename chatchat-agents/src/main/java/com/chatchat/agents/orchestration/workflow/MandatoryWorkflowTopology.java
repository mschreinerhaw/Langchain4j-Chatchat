package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstObject;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/**
 * Interprets the persisted workflow topology used by mandatory-tool recovery.
 *
 * <p>This component deliberately has no model, runtime, or tool-execution dependency. It turns
 * authoritative DAG/configuration snapshots into deterministic ordering and predecessor traces.</p>
 */
public final class MandatoryWorkflowTopology {

    private final AgentToolNameResolver toolNames;
    private final AgentWorkflowToolResolver workflowTools;

    public MandatoryWorkflowTopology(AgentToolNameResolver toolNames,
                                     AgentWorkflowToolResolver workflowTools) {
        this.toolNames = Objects.requireNonNull(toolNames, "toolNames");
        this.workflowTools = Objects.requireNonNull(workflowTools, "workflowTools");
    }

    public List<String> dependencyOrderedFallbackTools(Object authoritativeWorkflowDag,
                                                       Object mcpWorkflow,
                                                       List<String> mandatoryTools,
                                                       Set<String> completedTools) {
        List<String> missing = workflowTools.missingMandatoryTools(mandatoryTools, completedTools);
        if (missing.size() < 2) {
            return missing;
        }
        List<String> remaining = new ArrayList<>(missing);
        List<String> ordered = new ArrayList<>(missing.size());
        while (!remaining.isEmpty()) {
            String ready = remaining.stream()
                .filter(tool -> dependencies(authoritativeWorkflowDag, mcpWorkflow, tool).stream()
                    .noneMatch(dependency -> remaining.stream()
                        .anyMatch(candidate -> toolNames.sameToolName(dependency, candidate))))
                .findFirst()
                .orElse(null);
            if (ready == null) {
                // Validation should reject cycles. Retain deterministic behavior for corrupt snapshots.
                ordered.addAll(remaining);
                break;
            }
            ordered.add(ready);
            remaining.remove(ready);
        }
        return List.copyOf(ordered);
    }

    public List<InteractionToolTrace> predecessorTraces(Object authoritativeWorkflowDag,
                                                        List<String> mandatoryTools,
                                                        String fallbackTool,
                                                        List<InteractionToolTrace> traces) {
        if (mandatoryTools == null || mandatoryTools.isEmpty()
            || fallbackTool == null || traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<String> configured = authoritativeDependencies(authoritativeWorkflowDag, fallbackTool);
        if (configured != null) {
            return successfulTraces(configured, traces);
        }
        int fallbackIndex = -1;
        for (int index = 0; index < mandatoryTools.size(); index++) {
            if (toolNames.sameToolName(fallbackTool, mandatoryTools.get(index))) {
                fallbackIndex = index;
                break;
            }
        }
        if (fallbackIndex <= 0) {
            return List.of();
        }
        return successfulTraces(mandatoryTools.subList(0, fallbackIndex), traces);
    }

    private Set<String> dependencies(Object dag, Object workflow, String tool) {
        Set<String> dependencies = new LinkedHashSet<>();
        List<String> authoritative = authoritativeDependencies(dag, tool);
        if (authoritative != null) {
            dependencies.addAll(authoritative);
        }
        dependencies.addAll(configuredDependencies(workflow, tool));
        return dependencies;
    }

    private List<String> configuredDependencies(Object rawWorkflow, String toolName) {
        Map<String, Object> workflow = asMap(rawWorkflow);
        if (workflow.isEmpty() || toolName == null || toolName.isBlank()) {
            return List.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        if (workflow.get("steps") instanceof Iterable<?> values) {
            for (Object value : values) {
                Map<String, Object> step = asMap(value);
                String stepTool = firstNonBlank(stringValue(step.get("tool")), stringValue(step.get("toolName")));
                if (stepTool == null) {
                    continue;
                }
                steps.add(step);
                for (Object alias : new Object[]{step.get("step"), step.get("order"), step.get("id"),
                    step.get("name"), stepTool}) {
                    String text = stringValue(alias);
                    if (text != null && !text.isBlank()) {
                        aliases.putIfAbsent(text.trim().toLowerCase(Locale.ROOT), stepTool);
                    }
                }
            }
        }
        Set<String> dependencies = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            String stepTool = firstNonBlank(stringValue(step.get("tool")), stringValue(step.get("toolName")));
            if (toolNames.sameToolName(toolName, stepTool)) {
                collect(dependencies, firstObject(step, "dependsOn", "depends_on"), aliases);
            }
        }
        Map<String, Object> toolDependencies = asMap(firstObject(workflow, "toolDependencies", "tool_dependencies"));
        for (Map.Entry<String, Object> entry : toolDependencies.entrySet()) {
            if (!toolNames.sameToolName(toolName, entry.getKey())) {
                continue;
            }
            Map<String, Object> contract = asMap(entry.getValue());
            if (contract.isEmpty()) {
                collect(dependencies, entry.getValue(), aliases);
            } else {
                collect(dependencies, firstObject(contract, "dependsOn", "depends_on"), aliases);
                collect(dependencies, firstObject(contract, "requiredDependsOn", "required_depends_on",
                    "requiredDependencies", "required_dependencies"), aliases);
            }
        }
        return List.copyOf(dependencies);
    }

    private void collect(Set<String> target, Object rawDependencies, Map<String, String> aliases) {
        if (rawDependencies == null) {
            return;
        }
        Collection<?> values = rawDependencies instanceof Collection<?> collection
            ? collection : List.of(rawDependencies);
        for (Object value : values) {
            String dependency = stringValue(value);
            if (dependency != null && !dependency.isBlank()) {
                target.add(aliases.getOrDefault(dependency.trim().toLowerCase(Locale.ROOT), dependency.trim()));
            }
        }
    }

    /** Null means no authoritative node; an empty list means an explicit root node. */
    private List<String> authoritativeDependencies(Object rawDag, String fallbackTool) {
        if (!(rawDag instanceof Iterable<?> nodes) || fallbackTool == null || fallbackTool.isBlank()) {
            return null;
        }
        for (Object rawNode : nodes) {
            Map<String, Object> node = asMap(rawNode);
            String nodeTool = firstNonBlank(stringValue(node.get("tool")), stringValue(node.get("toolName")));
            if (!toolNames.sameToolName(fallbackTool, nodeTool)) {
                continue;
            }
            Object rawDependencies = firstObject(node, "dependsOnTools", "depends_on_tools", "dependsOn");
            if (!(rawDependencies instanceof Iterable<?> dependencies)) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (Object dependency : dependencies) {
                String value = stringValue(dependency);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        }
        return null;
    }

    private List<InteractionToolTrace> successfulTraces(List<String> dependencyTools,
                                                        List<InteractionToolTrace> traces) {
        if (dependencyTools == null || dependencyTools.isEmpty()) {
            return List.of();
        }
        return traces.stream()
            .filter(Objects::nonNull)
            .filter(InteractionToolTrace::isSuccess)
            .filter(trace -> trace.getOutput() != null && !trace.getOutput().isBlank())
            .filter(trace -> dependencyTools.stream()
                .anyMatch(tool -> toolNames.sameToolName(tool, trace.getToolName())))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }
}
