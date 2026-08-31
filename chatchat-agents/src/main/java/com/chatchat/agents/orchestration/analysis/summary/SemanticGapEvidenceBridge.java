package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.common.runtime.summary.analysis.semantic.SemanticClaimLifecycleContract;
import com.chatchat.common.runtime.summary.analysis.semantic.SemanticEvidenceGapContract;
import com.chatchat.common.runtime.summary.analysis.semantic.SemanticGapResolutionPolicy;
import com.chatchat.common.runtime.summary.analysis.semantic.SemanticOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Connects rejected semantic claims to the existing evidence-gap loop. */
final class SemanticGapEvidenceBridge {

    private static final Logger log = LoggerFactory.getLogger(SemanticGapEvidenceBridge.class);

    private final AgentRunResultAdapter runResultAdapter;
    private final String runIdAttribute;
    private final SemanticGapResolutionPolicy resolutionPolicy = new SemanticGapResolutionPolicy();

    SemanticGapEvidenceBridge(AgentRunResultAdapter runResultAdapter, String runIdAttribute) {
        this.runResultAdapter = runResultAdapter;
        this.runIdAttribute = runIdAttribute;
    }

    <T> T preflight(Supplier<T> analysis,
                           Supplier<T> emptyResult,
                           Map<String, Object> runtimeAttributes,
                           Map<String, Object> metadata) {
        try {
            return analysis.get();
        } catch (CancellationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            metadata.put("semanticClaimPreflightFailed", true);
            metadata.put("semanticClaimPreflightFailure",
                ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("Semantic claim preflight failed; returned facts and limitations are preserved. "
                    + "runId={} errorType={} error={}",
                text(runtimeAttributes == null ? null : runtimeAttributes.get(runIdAttribute)),
                ex.getClass().getName(), ex.getMessage());
            return emptyResult.get();
        }
    }

    Map<String, Object> merge(Map<String, Object> evidence,
                                     List<AnalysisSummaryResult> summaries,
                                     int iteration,
        Map<String, Object> runtimeAttributes,
                                     Map<String, Object> metadata) {
        Map<String, Map<String, Object>> gapsById = collect(summaries, "semanticGaps", "gapId");
        if (gapsById.isEmpty()) {
            Map<String, Object> admitted = new LinkedHashMap<>(evidence == null ? Map.of() : evidence);
            mergeClaimLifecycle(summaries, admitted, metadata);
            return Collections.unmodifiableMap(admitted);
        }
        Map<String, Map<String, Object>> requestsById =
            collect(summaries, "semanticGapRequests", "questionId");
        List<Map<String, Object>> semanticGaps = List.copyOf(gapsById.values());
        GapControl gapControl = controlGaps(semanticGaps, summaries, metadata);
        List<Map<String, Object>> semanticGapRequests = requestsById.values().stream()
            .filter(request -> !gapControl.terminalGapIds().contains(text(request.get("questionId"))))
            .toList();
        Map<String, Object> merged = new LinkedHashMap<>(evidence == null ? Map.of() : evidence);
        merged.put("semanticGaps", semanticGaps);
        merged.put("semanticGapRequests", semanticGapRequests);
        merged.put("semanticGapResolutionStates", gapControl.states());
        List<Map<String, Object>> existingRequests = asMapList(merged.get("gapRequests")).stream()
            .filter(request -> !gapControl.terminalGapIds().contains(text(request.get("questionId"))))
            .toList();
        merged.put("gapRequests", mergeRequests(existingRequests, semanticGapRequests));
        List<Object> missing = new ArrayList<>();
        if (merged.get("remainingMissing") instanceof Iterable<?> values) values.forEach(missing::add);
        missing.addAll(semanticGaps);
        merged.put("remainingMissing", List.copyOf(missing));
        if (!semanticGapRequests.isEmpty()) merged.put("sufficient", false);
        mergeClaimLifecycle(summaries, merged, metadata);
        record(semanticGaps, semanticGapRequests, iteration, runtimeAttributes, metadata);
        return Collections.unmodifiableMap(merged);
    }

    private GapControl controlGaps(List<Map<String, Object>> gaps,
                                   List<AnalysisSummaryResult> summaries,
                                   Map<String, Object> metadata) {
        Map<String, SemanticGapResolutionPolicy.State> previous = new LinkedHashMap<>();
        for (Map<String, Object> value : asMapList(metadata.get("semanticGapResolutionStates"))) {
            SemanticGapResolutionPolicy.State state = resolutionState(value);
            previous.put(state.gapFingerprint(), state);
        }
        String evidenceVersion = aggregateVersion(summaries, "contentSha256");
        String capabilityVersion = aggregateVersion(summaries, "analysisSemanticContract");
        int maxAttempts = integer(metadata.get("semanticGapMaxAttempts"),
            SemanticGapResolutionPolicy.DEFAULT_MAX_ATTEMPTS);
        List<Map<String, Object>> states = new ArrayList<>();
        Set<String> terminal = new LinkedHashSet<>();
        for (Map<String, Object> value : gaps) {
            SemanticEvidenceGapContract.Gap gap = gap(value);
            SemanticGapResolutionPolicy.State state = resolutionPolicy.evaluate(
                gap, evidenceVersion, capabilityVersion, previous.get(gap.gapId()), maxAttempts);
            states.add(state.toMap());
            if (state.terminal()) terminal.add(gap.gapId());
        }
        metadata.put("semanticGapResolutionStates", List.copyOf(states));
        metadata.put("semanticGapTerminalCount", terminal.size());
        return new GapControl(List.copyOf(states), Set.copyOf(terminal));
    }

    private void mergeClaimLifecycle(List<AnalysisSummaryResult> summaries,
                                     Map<String, Object> evidence,
                                     Map<String, Object> metadata) {
        Map<String, Map<String, Object>> current = collect(summaries, "claimLifecycle", "claimFingerprint");
        Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
        for (Map<String, Object> item : asMapList(metadata.get("semanticClaimLifecycle"))) {
            previous.put(text(item.get("claimFingerprint")), item);
        }
        List<Map<String, Object>> history = new ArrayList<>(asMapList(metadata.get("semanticClaimHistory")));
        List<Map<String, Object>> latest = new ArrayList<>();
        for (Map<String, Object> draft : current.values()) {
            String fingerprint = text(draft.get("claimFingerprint"));
            Map<String, Object> prior = previous.get(fingerprint);
            if (prior != null && java.util.Objects.equals(prior.get("evidenceVersion"), draft.get("evidenceVersion"))
                && java.util.Objects.equals(prior.get("state"), draft.get("state"))) {
                latest.add(prior);
                continue;
            }
            SemanticClaimLifecycleContract.Revision revision = SemanticClaimLifecycleContract.evolve(
                fingerprint, text(draft.get("evidenceVersion")),
                "ADMITTED".equals(text(draft.get("state"))), strings(draft.get("rejectionCodes")),
                text(draft.get("semanticGapId")), prior == null ? null : claimRevision(prior));
            latest.add(revision.toMap());
            history.add(revision.toMap());
        }
        evidence.put("claimLifecycle", List.copyOf(latest));
        metadata.put("semanticClaimLifecycle", List.copyOf(latest));
        metadata.put("semanticClaimHistory", List.copyOf(history));
    }

    private Map<String, Map<String, Object>> collect(List<AnalysisSummaryResult> summaries,
                                                      String evidenceKey,
                                                      String identityKey) {
        Map<String, Map<String, Object>> collected = new LinkedHashMap<>();
        for (AnalysisSummaryResult summary : summaries == null ? List.<AnalysisSummaryResult>of() : summaries) {
            for (Map<String, Object> value : asMapList(summary.evidence().get(evidenceKey))) {
                String identity = text(value.get(identityKey));
                collected.putIfAbsent(identity == null ? value.toString() : identity, value);
            }
        }
        return collected;
    }

    private List<Map<String, Object>> mergeRequests(List<Map<String, Object>> existing,
                                                     List<Map<String, Object>> additions) {
        List<Map<String, Object>> merged = new ArrayList<>(existing);
        Set<String> identities = new LinkedHashSet<>();
        existing.forEach(value -> identities.add(text(value.get("questionId"))));
        additions.stream().filter(value -> identities.add(text(value.get("questionId"))))
            .forEach(merged::add);
        return List.copyOf(merged);
    }

    private void record(List<Map<String, Object>> gaps,
                        List<Map<String, Object>> requests,
                        int iteration,
                        Map<String, Object> runtimeAttributes,
                        Map<String, Object> metadata) {
        metadata.put("semanticClaimGapCount", gaps.size());
        metadata.put("semanticClaimActionableGapCount", requests.size());
        metadata.put("semanticClaimGaps", gaps);
        metadata.put("semanticClaimGapRequests", requests);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "semantic_evidence_gap");
        event.put("stage", "EVIDENCE_GAP_IDENTIFIED");
        event.put("iteration", iteration);
        event.put("gapCount", gaps.size());
        event.put("actionableGapCount", requests.size());
        event.put("semanticGaps", gaps);
        event.put("gapRequests", requests);
        runResultAdapter.recordRuntimeObservation(runtimeAttributes, runIdAttribute,
            requests.isEmpty()
                ? "业务分析发现部分结论证据不足，将在最终结果中说明限制。"
                : "业务分析发现 " + requests.size() + " 项可补充的数据需求，正在进入补证决策。",
            "business_analysis_progress", event);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) maps.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return List.copyOf(maps);
    }

    private SemanticEvidenceGapContract.Gap gap(Map<String, Object> value) {
        return new SemanticEvidenceGapContract.Gap(text(value.get("gapId")),
            enumValue(SemanticEvidenceGapContract.Route.class, value.get("route"),
                SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS),
            Set.copyOf(strings(value.get("rejectionCodes"))), text(value.get("requiredCapabilityId")),
            enumValue(SemanticOperation.class, value.get("requiredOperation"), null),
            Set.copyOf(strings(value.get("requiredFields"))), text(value.get("requiredUnit")),
            text(value.get("requiredGrain")), text(value.get("requiredTimeScope")),
            text(value.get("requiredPopulationScope")), Set.copyOf(strings(value.get("basedOnEvidenceReferences"))));
    }

    private SemanticGapResolutionPolicy.State resolutionState(Map<String, Object> value) {
        return new SemanticGapResolutionPolicy.State(text(value.get("gapFingerprint")),
            integer(value.get("attemptCount"), 1), text(value.get("evidenceVersion")),
            text(value.get("capabilityVersion")),
            enumValue(SemanticEvidenceGapContract.Route.class, value.get("lastResolution"),
                SemanticEvidenceGapContract.Route.ANALYZE_WITH_LIMITATIONS),
            enumValue(SemanticGapResolutionPolicy.TerminalReason.class, value.get("terminalReason"),
                SemanticGapResolutionPolicy.TerminalReason.NONE));
    }

    private SemanticClaimLifecycleContract.Revision claimRevision(Map<String, Object> value) {
        return new SemanticClaimLifecycleContract.Revision(text(value.get("claimId")),
            text(value.get("claimFingerprint")), integer(value.get("revision"), 1),
            text(value.get("parentClaimId")), text(value.get("evidenceVersion")),
            text(value.get("admissionVersion")),
            enumValue(SemanticClaimLifecycleContract.State.class, value.get("state"),
                SemanticClaimLifecycleContract.State.REJECTED),
            strings(value.get("transitions")).stream()
                .map(item -> enumValue(SemanticClaimLifecycleContract.State.class, item, null))
                .filter(java.util.Objects::nonNull).toList(),
            strings(value.get("rejectionCodes")), text(value.get("semanticGapId")));
    }

    private String aggregateVersion(List<AnalysisSummaryResult> summaries, String key) {
        List<Object> values = new ArrayList<>();
        for (AnalysisSummaryResult summary : summaries == null ? List.<AnalysisSummaryResult>of() : summaries) {
            Object value = summary.evidence().get(key);
            if (value != null) values.add(value);
        }
        return SemanticClaimLifecycleContract.fingerprint(values.stream()
            .map(String::valueOf).sorted().toList());
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String text = text(item);
            if (text != null && !result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback) {
        String text = text(value);
        if (text == null) return fallback;
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private record GapControl(List<Map<String, Object>> states, Set<String> terminalGapIds) {
    }
}
