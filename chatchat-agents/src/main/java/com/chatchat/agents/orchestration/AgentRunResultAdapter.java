package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentObservationPipeline;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRunStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts orchestration metadata to the stable agent runtime result contract.
 */
class AgentRunResultAdapter {

    private final AgentRunStore runStore;
    private final AgentObservationPipeline observationPipeline;

    AgentRunResultAdapter(AgentRunStore runStore, AgentObservationPipeline observationPipeline) {
        this.runStore = runStore;
        this.observationPipeline = observationPipeline;
    }

    AgentRunResult toAgentRunResult(String runId, AgentOrchestrator.AgentExecutionResult result) {
        Map<String, Object> metadata = result == null || result.metadata() == null
            ? Map.of()
            : new LinkedHashMap<>(result.metadata());
        return AgentRunResult.builder()
            .runId(runId)
            .status(booleanValue(metadata.get("confirmationRequired"))
                ? AgentRunStatus.WAITING_CONFIRMATION
                : AgentRunStatus.COMPLETED)
            .answer(result == null ? "" : result.answer())
            .stopReason(stringValue(metadata.get("stopReason")))
            .confirmationRequired(booleanValue(metadata.get("confirmationRequired")))
            .errorMessage(stringValue(metadata.get("errorMessage")))
            .steps(toAgentRunSteps(metadata.get("plannerSteps")))
            .observations(toAgentObservations(metadata.get("observations")))
            .events(List.of())
            .toolTraces(result == null || result.toolTraces() == null ? List.of() : result.toolTraces())
            .metadata(metadata)
            .build();
    }

    void recordRuntimeStep(Map<String, Object> runtimeAttributes, String runIdAttribute, Map<String, Object> step) {
        String runId = runtimeAttributes == null ? null : stringValue(runtimeAttributes.get(runIdAttribute));
        if (runId == null || runId.isBlank() || step == null || step.isEmpty()) {
            return;
        }
        runStore.recordStep(runId, toAgentRunStep(step, 1));
    }

    List<String> runtimeObservationList(String runId) {
        return new ArrayList<>() {
            @Override
            public boolean add(String observation) {
                boolean added = super.add(observation);
                if (added) {
                    recordRuntimeObservation(runId, observation);
                }
                return added;
            }

            @Override
            public void add(int index, String observation) {
                super.add(index, observation);
                recordRuntimeObservation(runId, observation);
            }

            @Override
            public boolean addAll(Collection<? extends String> observations) {
                boolean changed = false;
                if (observations != null) {
                    for (String observation : observations) {
                        changed |= add(observation);
                    }
                }
                return changed;
            }
        };
    }

    private List<AgentRunStep> toAgentRunSteps(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<AgentRunStep> steps = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> step = asMap(item);
            if (step.isEmpty()) {
                continue;
            }
            steps.add(toAgentRunStep(step, steps.size() + 1));
        }
        return steps;
    }

    private AgentRunStep toAgentRunStep(Map<String, Object> step, int fallbackStep) {
        return AgentRunStep.builder()
            .step(firstInteger(step.get("step"), fallbackStep))
            .action(stringValue(step.get("action")))
            .toolName(stringValue(step.get("toolName")))
            .resolvedToolName(stringValue(step.get("resolvedToolName")))
            .reason(stringValue(step.get("reason")))
            .executionPlan(asMap(step.get("executionPlan")))
            .answerPreview(stringValue(step.get("answerPreview")))
            .plannedAt(firstLong(step.get("plannedAt"), 0L))
            .observationCount(firstInteger(step.get("observationCount"), 0))
            .build();
    }

    private void recordRuntimeObservation(String runId, String observation) {
        if (runId == null || runId.isBlank() || observation == null || observation.isBlank()) {
            return;
        }
        runStore.recordObservation(runId, observationPipeline.fromText(observation));
    }

    private List<AgentObservation> toAgentObservations(Object value) {
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            texts.add(String.valueOf(item));
        }
        return observationPipeline.fromTexts(texts);
    }

    private Map<String, Object> asMap(Object data) {
        if (data instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    values.put(String.valueOf(key), value);
                }
            });
            return values;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private int firstInteger(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long firstLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
