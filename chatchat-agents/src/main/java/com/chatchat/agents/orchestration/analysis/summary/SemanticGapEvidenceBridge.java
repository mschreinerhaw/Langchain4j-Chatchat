package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
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
public final class SemanticGapEvidenceBridge {

    private static final Logger log = LoggerFactory.getLogger(SemanticGapEvidenceBridge.class);

    private final AgentRunResultAdapter runResultAdapter;
    private final String runIdAttribute;

    public SemanticGapEvidenceBridge(AgentRunResultAdapter runResultAdapter, String runIdAttribute) {
        this.runResultAdapter = runResultAdapter;
        this.runIdAttribute = runIdAttribute;
    }

    public <T> T preflight(Supplier<T> analysis,
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

    public Map<String, Object> merge(Map<String, Object> evidence,
                                     List<AnalysisSummaryResult> summaries,
                                     int iteration,
                                     Map<String, Object> runtimeAttributes,
                                     Map<String, Object> metadata) {
        Map<String, Map<String, Object>> gapsById = collect(summaries, "semanticGaps", "gapId");
        if (gapsById.isEmpty()) return evidence;
        Map<String, Map<String, Object>> requestsById =
            collect(summaries, "semanticGapRequests", "questionId");
        List<Map<String, Object>> semanticGaps = List.copyOf(gapsById.values());
        List<Map<String, Object>> semanticGapRequests = List.copyOf(requestsById.values());
        Map<String, Object> merged = new LinkedHashMap<>(evidence == null ? Map.of() : evidence);
        merged.put("semanticGaps", semanticGaps);
        merged.put("semanticGapRequests", semanticGapRequests);
        merged.put("gapRequests", mergeRequests(asMapList(merged.get("gapRequests")), semanticGapRequests));
        List<Object> missing = new ArrayList<>();
        if (merged.get("remainingMissing") instanceof Iterable<?> values) values.forEach(missing::add);
        missing.addAll(semanticGaps);
        merged.put("remainingMissing", List.copyOf(missing));
        if (!semanticGapRequests.isEmpty()) merged.put("sufficient", false);
        record(semanticGaps, semanticGapRequests, iteration, runtimeAttributes, metadata);
        return Collections.unmodifiableMap(merged);
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

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
