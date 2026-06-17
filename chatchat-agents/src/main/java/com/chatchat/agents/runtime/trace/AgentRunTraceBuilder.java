package com.chatchat.agents.runtime.trace;

import com.chatchat.agents.evidence.EvidenceAnswerGroundingGuard;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.common.interaction.InteractionToolTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentRunTraceBuilder {

    private static final int PREVIEW_LIMIT = 600;

    private final EvidenceAnswerGroundingGuard groundingGuard = new EvidenceAnswerGroundingGuard();

    public AgentRunTrace fromRun(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("Agent run is required");
        }
        AgentRunRequest request = run.request();
        AgentRunResult result = run.result();
        Map<String, Object> metadata = result == null || result.metadata() == null
            ? run.metadata()
            : result.metadata();
        List<String> observationTexts = run.observations().stream()
            .map(AgentObservation::content)
            .filter(value -> value != null && !value.isBlank())
            .toList();
        List<Map<String, Object>> availableCitations = citationMaps(metadata.get("availableEvidenceCitations"));
        if (availableCitations.isEmpty()) {
            availableCitations = groundingGuard.extractCitationMaps(String.join("\n", observationTexts));
        }
        List<Map<String, Object>> usedCitations = answerCitations(result, metadata);
        List<String> unsupported = unsupportedCitations(usedCitations, availableCitations);
        return new AgentRunTrace(
            AgentRunTrace.CONTRACT_VERSION,
            run.runId(),
            request == null ? null : request.getRequestId(),
            request == null ? null : request.getConversationId(),
            request == null ? null : request.getTenantId(),
            request == null ? null : request.getUserId(),
            request == null ? null : request.getQuery(),
            run.status(),
            run.startedAt(),
            run.finishedAt(),
            toolCallTraces(run, result),
            evidenceTraces(availableCitations, usedCitations, observationTexts),
            answerTrace(result, metadata, usedCitations),
            new GroundingTrace(
                stringValue(metadata.getOrDefault("groundingStatus", "not_evaluated")),
                availableCitations,
                usedCitations,
                missingInfo(metadata),
                unsupported
            ),
            failureReasons(run, metadata, unsupported),
            run.events()
        );
    }

    private List<ToolCallTrace> toolCallTraces(AgentRun run, AgentRunResult result) {
        List<ToolCallTrace> traces = new ArrayList<>();
        List<InteractionToolTrace> interactionTraces = result == null ? List.of() : result.toolTraces();
        for (int i = 0; i < interactionTraces.size(); i++) {
            InteractionToolTrace trace = interactionTraces.get(i);
            if (trace == null) {
                continue;
            }
            traces.add(new ToolCallTrace(
                i + 1,
                trace.getToolName(),
                trace.getDisplayName(),
                trace.isSuccess(),
                trace.getInput(),
                preview(trace.getOutput(), PREVIEW_LIMIT),
                trace.getErrorMessage(),
                trace.getDurationMs(),
                trace.getStartedAt(),
                trace.getFinishedAt(),
                asMap(trace.getRuntimeMetadata() == null ? null : trace.getRuntimeMetadata().get("governance")),
                trace.getRuntimeMetadata()
            ));
        }
        if (!traces.isEmpty()) {
            return traces;
        }
        return run.steps().stream()
            .filter(step -> step != null && firstText(step.resolvedToolName(), step.toolName()) != null)
            .map(this::stepTrace)
            .toList();
    }

    private ToolCallTrace stepTrace(AgentRunStep step) {
        Map<String, Object> executionPlan = step.executionPlan();
        return new ToolCallTrace(
            step.step(),
            firstText(step.resolvedToolName(), step.toolName()),
            null,
            null,
            asMap(executionPlan.get("input")),
            null,
            stringValue(executionPlan.get("errorMessage")),
            longValue(firstPresent(executionPlan, "durationMs", "toolExecutionTimeMs")),
            step.plannedAt() > 0 ? step.plannedAt() : null,
            null,
            asMap(executionPlan.get("governance")),
            executionPlan
        );
    }

    private List<EvidenceTrace> evidenceTraces(List<Map<String, Object>> availableCitations,
                                               List<Map<String, Object>> usedCitations,
                                               List<String> observations) {
        Set<String> usedRefs = refIds(usedCitations);
        List<EvidenceTrace> traces = new ArrayList<>();
        for (Map<String, Object> citation : availableCitations) {
            String refId = stringValue(citation.get("refId"));
            if (refId == null || refId.isBlank()) {
                continue;
            }
            traces.add(new EvidenceTrace(
                refId,
                stringValue(citation.get("type")),
                evidenceSource(citation),
                stringValue(citation.get("toolName")),
                usedRefs.contains(refId),
                stringValue(citation.get("policyStatus")),
                observationPreview(refId, observations),
                citation
            ));
        }
        return traces;
    }

    @SuppressWarnings("unchecked")
    private AnswerTrace answerTrace(AgentRunResult result,
                                    Map<String, Object> metadata,
                                    List<Map<String, Object>> usedCitations) {
        Map<String, Object> evidenceAnswer = asMap(metadata.get("evidenceAnswer"));
        List<String> missingInfo = stringList(evidenceAnswer.get("missingInfo"));
        return new AnswerTrace(
            result == null ? null : result.answer(),
            stringValue(metadata.get("answerContractVersion")),
            usedCitations,
            stringValue(evidenceAnswer.get("confidence")),
            missingInfo
        );
    }

    private List<Map<String, Object>> answerCitations(AgentRunResult result, Map<String, Object> metadata) {
        Map<String, Object> evidenceAnswer = asMap(metadata.get("evidenceAnswer"));
        List<Map<String, Object>> citations = citationMaps(evidenceAnswer.get("citations"));
        if (!citations.isEmpty()) {
            return citations;
        }
        return groundingGuard.extractCitationMaps(result == null ? null : result.answer());
    }

    private List<String> failureReasons(AgentRun run, Map<String, Object> metadata, List<String> unsupported) {
        List<String> reasons = new ArrayList<>();
        if (run.errorMessage() != null && !run.errorMessage().isBlank()) {
            reasons.add(run.errorMessage());
        }
        String errorMessage = stringValue(metadata.get("errorMessage"));
        if (errorMessage != null && !errorMessage.isBlank()) {
            reasons.add(errorMessage);
        }
        if ("needs_review".equals(metadata.get("groundingStatus"))) {
            reasons.add("grounding_needs_review");
        }
        if (!unsupported.isEmpty()) {
            reasons.add("unsupported_citations");
        }
        return List.copyOf(new LinkedHashSet<>(reasons));
    }

    private List<String> missingInfo(Map<String, Object> metadata) {
        Map<String, Object> evidenceAnswer = asMap(metadata.get("evidenceAnswer"));
        return stringList(evidenceAnswer.get("missingInfo"));
    }

    private List<String> unsupportedCitations(List<Map<String, Object>> used, List<Map<String, Object>> available) {
        if (used.isEmpty() || available.isEmpty()) {
            return List.of();
        }
        Set<String> availableRefs = refIds(available);
        return used.stream()
            .map(item -> stringValue(item.get("refId")))
            .filter(value -> value != null && !availableRefs.contains(value))
            .distinct()
            .toList();
    }

    private Set<String> refIds(List<Map<String, Object>> citations) {
        Set<String> refs = new LinkedHashSet<>();
        for (Map<String, Object> citation : citations == null ? List.<Map<String, Object>>of() : citations) {
            String refId = stringValue(citation.get("refId"));
            if (refId != null && !refId.isBlank()) {
                refs.add(refId);
            }
        }
        return refs;
    }

    private String evidenceSource(Map<String, Object> citation) {
        return firstText(
            stringValue(citation.get("fileName")),
            firstText(stringValue(citation.get("source")), stringValue(citation.get("domain")))
        );
    }

    private String observationPreview(String refId, List<String> observations) {
        if (refId == null || observations == null) {
            return null;
        }
        for (String observation : observations) {
            if (observation != null && observation.contains(refId)) {
                return preview(observation, PREVIEW_LIMIT);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> citationMaps(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                values.add(map);
            }
        }
        return List.copyOf(values);
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                values.put(String.valueOf(key), item);
            }
        });
        return values;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(String::valueOf)
            .toList();
    }

    private Object firstPresent(Map<String, Object> map, String first, String second) {
        if (map == null) {
            return null;
        }
        Object value = map.get(first);
        return value == null ? map.get(second) : value;
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String preview(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int safeLimit = Math.max(80, limit);
        return normalized.length() <= safeLimit ? normalized : normalized.substring(0, safeLimit);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
