package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.capability.CapabilityId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded DAG of agent tasks. Source (LOCAL/GROUP/EXTERNAL) is resolved by the registry, never by task type. */
public record AgentCollaborationPlan(List<Task> tasks) {
    public static final String CONTEXT_ATTRIBUTE = "runtime.agent.collaborationPlan";
    public static final int MAX_TASKS = 5;

    public AgentCollaborationPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        if (tasks.isEmpty() || tasks.size() > MAX_TASKS)
            throw new IllegalArgumentException("Agent collaboration plan requires 1.." + MAX_TASKS + " tasks");
        Set<String> completed = new HashSet<>();
        for (Task task : tasks) {
            if (task == null || !completed.add(task.taskId()))
                throw new IllegalArgumentException("Duplicate or missing agent taskId");
            if (!completed.containsAll(task.dependsOn()) || task.dependsOn().contains(task.taskId()))
                throw new IllegalArgumentException("Agent tasks must be topologically ordered with known dependencies");
        }
    }

    public static AgentCollaborationPlan from(Object raw) {
        if (raw instanceof AgentCollaborationPlan plan) return plan;
        if (!(raw instanceof Map<?, ?> map) || !(map.get("tasks") instanceof List<?> items))
            throw new IllegalArgumentException("collaborationPlan.tasks is required");
        if (items.size() > MAX_TASKS) throw new IllegalArgumentException("Too many agent collaboration tasks");
        List<Task> tasks = new ArrayList<>();
        for (Object item : items) tasks.add(Task.from(item));
        return new AgentCollaborationPlan(tasks);
    }

    public record Task(String taskId, String agentId, CapabilityId capability, String instruction,
                       AgentExecutionMode mode, List<String> dependsOn) {
        public Task {
            if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid collaboration taskId");
            if (taskId.equals("evidence") || taskId.equals("merge"))
                throw new IllegalArgumentException("Reserved collaboration taskId: " + taskId);
            agentId = agentId == null ? "" : agentId.trim();
            if (agentId.length() > 128) throw new IllegalArgumentException("Agent task agentId is too long");
            if (capability == null) throw new IllegalArgumentException("Agent task capability is required");
            if (instruction == null || instruction.isBlank() || instruction.length() > 10000)
                throw new IllegalArgumentException("Agent task instruction is required (1..10000 chars)");
            mode = mode == null ? AgentExecutionMode.DOMAIN_INFERENCE : mode;
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            if (dependsOn.size() > MAX_TASKS || new HashSet<>(dependsOn).size() != dependsOn.size())
                throw new IllegalArgumentException("Agent task dependencies must be unique and bounded");
        }

        static Task from(Object raw) {
            if (raw instanceof Task task) return task;
            if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Agent task must be an object");
            if (!map.keySet().stream().allMatch(key -> key instanceof String name &&
                Set.of("taskId", "agentId", "capability", "instruction", "mode", "dependsOn").contains(name)))
                throw new IllegalArgumentException("Agent task contains unsupported fields");
            Object dependencies = map.get("dependsOn");
            List<String> dependsOn = dependencies instanceof Iterable<?> values
                ? java.util.stream.StreamSupport.stream(values.spliterator(), false).map(String::valueOf).toList()
                : List.of();
            return new Task(text(map.get("taskId")), text(map.get("agentId")),
                CapabilityId.parse(text(map.get("capability"))), text(map.get("instruction")),
                AgentExecutionMode.parse(map.get("mode")), dependsOn);
        }
        private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    }
}
