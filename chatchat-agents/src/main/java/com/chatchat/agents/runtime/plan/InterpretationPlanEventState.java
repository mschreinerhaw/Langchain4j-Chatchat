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
    Set<Integer> failedStepIds
) {

    static InterpretationPlanEventState from(List<AgentRunEvent> events, Set<Integer> fallbackCompletedStepIds) {
        Set<Integer> completed = new LinkedHashSet<>(fallbackCompletedStepIds == null ? Set.of() : fallbackCompletedStepIds);
        Set<Integer> failed = new LinkedHashSet<>();
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
            }
        }
        return new InterpretationPlanEventState(completed, failed);
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
}
