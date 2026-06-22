package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record InterpretationPlanEventState(
    Set<Integer> completedStepIds,
    Set<Integer> failedStepIds,
    Set<Integer> immutableStepIds,
    Set<String> blockedTools,
    Set<String> allowOnlyActions
) {

    static InterpretationPlanEventState from(List<AgentRunEvent> events, Set<Integer> fallbackCompletedStepIds) {
        Set<Integer> completed = new LinkedHashSet<>(fallbackCompletedStepIds == null ? Set.of() : fallbackCompletedStepIds);
        Set<Integer> failed = new LinkedHashSet<>();
        Set<Integer> immutableSteps = new LinkedHashSet<>();
        Set<String> blockedTools = new LinkedHashSet<>();
        Set<String> allowOnlyActions = new LinkedHashSet<>();
        if (events != null) {
            for (AgentRunEvent event : events) {
                if (event == null || event.type() != AgentRunEventType.OBSERVATION_RECORDED) {
                    continue;
                }
                Map<String, Object> metadata = asMap(event.payload() == null ? null : event.payload().get("metadata"));
                Integer stepId = integerValue(firstPresent(metadata, "interpretationPlanStepId", "workflowStepId", "stepId"));
                if (stepId == null) {
                    continue;
                }
                Boolean success = booleanValue(firstPresent(metadata, "success", "toolSuccess"));
                if (Boolean.FALSE.equals(success)) {
                    failed.add(stepId);
                    completed.remove(stepId);
                } else if (Boolean.TRUE.equals(success)) {
                    completed.add(stepId);
                    failed.remove(stepId);
                }
                Map<String, Object> executionLock = asMap(metadata.get("executionLock"));
                if (locked(executionLock)) {
                    Map<String, Object> constraints = asMap(executionLock.get("executionConstraints"));
                    integerSet(firstPresent(constraints, "immutable_steps", "immutableSteps"))
                        .forEach(immutableSteps::add);
                    stringSet(firstPresent(constraints, "blocked_tools", "blockedTools"))
                        .forEach(blockedTools::add);
                    stringSet(firstPresent(constraints, "allow_only", "allowOnly"))
                        .forEach(allowOnlyActions::add);
                    integerSet(firstPresent(executionLock, "lockedSteps", "immutable_steps", "immutableSteps"))
                        .forEach(immutableSteps::add);
                    Map<String, Object> lockGraph = asMap(executionLock.get("lockGraph"));
                    Map<String, Object> dagFreeze = asMap(lockGraph.get("dagFreeze"));
                    if (frozen(dagFreeze)) {
                        stringSet(firstPresent(dagFreeze, "blockedTools", "blocked_tools"))
                            .forEach(blockedTools::add);
                        stringSet(firstPresent(dagFreeze, "allowedActions", "allow_only", "allowOnly"))
                            .forEach(allowOnlyActions::add);
                        immutableSteps.addAll(hardLockStepIds(lockGraph));
                    }
                }
            }
        }
        return new InterpretationPlanEventState(completed, failed, immutableSteps, blockedTools, allowOnlyActions);
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || values.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    values.put(String.valueOf(key), item);
                }
            });
            return values;
        }
        return Map.of();
    }

    private static Set<Integer> integerSet(Object value) {
        Set<Integer> values = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Integer parsed = integerValue(item);
                if (parsed != null) {
                    values.add(parsed);
                }
            }
            return values;
        }
        Integer single = integerValue(value);
        if (single != null) {
            values.add(single);
        }
        return values;
    }

    private static Set<String> stringSet(Object value) {
        Set<String> values = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = stringValue(item);
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
            return values;
        }
        String text = stringValue(value);
        if (text != null && !text.isBlank()) {
            values.add(text.trim());
        }
        return values;
    }

    private static boolean locked(Map<String, Object> executionLock) {
        if (executionLock == null || executionLock.isEmpty()) {
            return false;
        }
        Object status = firstPresent(executionLock, "status");
        if (status != null) {
            return "LOCKED".equalsIgnoreCase(String.valueOf(status));
        }
        return Boolean.TRUE.equals(booleanValue(firstPresent(executionLock, "lock")));
    }

    private static boolean frozen(Map<String, Object> dagFreeze) {
        if (dagFreeze == null || dagFreeze.isEmpty()) {
            return false;
        }
        String status = stringValue(firstPresent(dagFreeze, "status"));
        return "FULLY_FROZEN".equalsIgnoreCase(status) || "PARTIALLY_FROZEN".equalsIgnoreCase(status);
    }

    private static Set<Integer> hardLockStepIds(Map<String, Object> lockGraph) {
        Set<Integer> values = new LinkedHashSet<>();
        Object locks = lockGraph == null ? null : lockGraph.get("locks");
        if (!(locks instanceof Iterable<?> iterable)) {
            return values;
        }
        for (Object item : iterable) {
            Map<String, Object> lock = asMap(item);
            if (!"HARD".equalsIgnoreCase(stringValue(lock.get("type")))) {
                continue;
            }
            Integer sourceStepId = integerValue(lock.get("sourceStepId"));
            if (sourceStepId != null) {
                values.add(sourceStepId);
            }
        }
        return values;
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
