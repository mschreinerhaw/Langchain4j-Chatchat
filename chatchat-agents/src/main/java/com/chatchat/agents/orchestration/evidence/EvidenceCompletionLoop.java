package com.chatchat.agents.orchestration.evidence;

import com.chatchat.common.runtime.analysis.execution.AdaptiveAnalysisController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Business-neutral evidence completion loop. Retrieval providers are supplied by
 * runtime contracts, so documents, databases, web search, or future sources all
 * follow the same retrieve -> reassess lifecycle without source-name branching.
 */
public final class EvidenceCompletionLoop {

    public Result run(Request request, Retriever retriever, Assessor assessor) {
        if (request == null || retriever == null || assessor == null) {
            throw new IllegalArgumentException("request, retriever and assessor are required");
        }
        int maxRounds = AdaptiveAnalysisController.boundedRecoveryRounds(request.maxRounds());
        List<EvidenceItem> evidence = new ArrayList<>(safe(request.initialEvidence()));
        List<Round> rounds = new ArrayList<>();
        boolean stalled = false;
        Assessment assessment = assessor.assess(request.query(), List.copyOf(evidence));
        for (int round = 1; !assessment.sufficient() && round <= maxRounds; round++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            List<String> missing = assessment.missingSourceIds().isEmpty()
                ? request.sources().stream().map(SourceContract::id).toList()
                : assessment.missingSourceIds();
            List<EvidenceItem> added = new ArrayList<>();
            for (SourceContract source : safe(request.sources())) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                if (!missing.contains(source.id())) continue;
                List<EvidenceItem> batch = retriever.retrieve(new RetrievalRequest(
                    request.query(), round, source, assessment.gaps()));
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                if (batch != null) added.addAll(batch.stream().filter(item -> item != null).toList());
            }
            List<EvidenceItem> fresh = deduplicate(added, evidence);
            evidence.addAll(fresh);
            Assessment next = assessor.assess(request.query(), List.copyOf(evidence));
            rounds.add(new Round(round, List.copyOf(missing), List.copyOf(fresh), next));
            if (fresh.isEmpty() && !next.sufficient()) {
                stalled = true;
                List<String> unresolved = next.missingSourceIds().isEmpty()
                    ? unresolvedSourceIds(request.sources(), evidence)
                    : next.missingSourceIds();
                assessment = new Assessment(false, next.score(), unresolved,
                    merge(next.gaps(), List.of("retrieval_returned_no_new_evidence")));
                break;
            }
            assessment = next;
        }
        String stopReason = assessment.sufficient() ? "evidence_sufficient"
            : stalled ? "no_new_evidence" : "max_rounds_reached";
        return new Result(List.copyOf(evidence), assessment, List.copyOf(rounds), stopReason);
    }

    private List<String> unresolvedSourceIds(List<SourceContract> sources, List<EvidenceItem> evidence) {
        Set<String> resolved = new LinkedHashSet<>();
        safe(evidence).stream().map(EvidenceItem::sourceId)
            .filter(value -> value != null && !value.isBlank()).forEach(resolved::add);
        return safe(sources).stream().map(SourceContract::id)
            .filter(id -> !resolved.contains(id)).toList();
    }

    private List<EvidenceItem> deduplicate(List<EvidenceItem> candidates, List<EvidenceItem> existing) {
        Set<EvidenceItem> seen = new LinkedHashSet<>(safe(existing));
        List<EvidenceItem> result = new ArrayList<>();
        safe(candidates).forEach(item -> {
            if (seen.add(item)) result.add(item);
        });
        return result;
    }

    private List<String> merge(List<String> left, List<String> right) {
        LinkedHashSet<String> values = new LinkedHashSet<>(safe(left));
        values.addAll(safe(right));
        return List.copyOf(values);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    @FunctionalInterface
    public interface Retriever {
        List<EvidenceItem> retrieve(RetrievalRequest request);
    }

    @FunctionalInterface
    public interface Assessor {
        Assessment assess(String query, List<EvidenceItem> evidence);
    }

    public record Request(String query, int maxRounds, List<SourceContract> sources,
                          List<EvidenceItem> initialEvidence) {
        public Request {
            sources = List.copyOf(safe(sources));
            initialEvidence = List.copyOf(safe(initialEvidence));
        }
    }

    public record SourceContract(String id, String kind, Map<String, Object> parameters) {
        public SourceContract {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("source id is required");
            kind = kind == null ? "unspecified" : kind;
            parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
        }
    }

    public record RetrievalRequest(String query, int round, SourceContract source, List<String> gaps) {
        public RetrievalRequest { gaps = List.copyOf(safe(gaps)); }
    }

    public record EvidenceItem(String id, String sourceId, String sourceKind, String text,
                               String locator, Map<String, Object> attributes) {
        public EvidenceItem {
            attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
        }
    }

    public record Assessment(boolean sufficient, double score, List<String> missingSourceIds,
                             List<String> gaps) {
        public Assessment {
            missingSourceIds = List.copyOf(safe(missingSourceIds));
            gaps = List.copyOf(safe(gaps));
        }
    }

    public record Round(int number, List<String> attemptedSourceIds, List<EvidenceItem> addedEvidence,
                        Assessment assessment) {}

    public record Result(List<EvidenceItem> evidence, Assessment assessment, List<Round> rounds,
                         String stopReason) {}
}
