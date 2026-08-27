package com.chatchat.agents.orchestration.evidence;

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
        int maxRounds = Math.max(0, request.maxRounds());
        List<EvidenceItem> evidence = new ArrayList<>(safe(request.initialEvidence()));
        List<Round> rounds = new ArrayList<>();
        Assessment assessment = assessor.assess(request.query(), List.copyOf(evidence));
        for (int round = 1; !assessment.sufficient() && round <= maxRounds; round++) {
            List<String> missing = assessment.missingSourceIds().isEmpty()
                ? request.sources().stream().map(SourceContract::id).toList()
                : assessment.missingSourceIds();
            List<EvidenceItem> added = new ArrayList<>();
            for (SourceContract source : safe(request.sources())) {
                if (!missing.contains(source.id())) continue;
                List<EvidenceItem> batch = retriever.retrieve(new RetrievalRequest(
                    request.query(), round, source, assessment.gaps()));
                if (batch != null) added.addAll(batch.stream().filter(item -> item != null).toList());
            }
            evidence.addAll(deduplicate(added, evidence));
            Assessment next = assessor.assess(request.query(), List.copyOf(evidence));
            rounds.add(new Round(round, List.copyOf(missing), List.copyOf(added), next));
            if (added.isEmpty()) {
                assessment = new Assessment(false, next.score(), next.missingSourceIds(),
                    merge(next.gaps(), List.of("retrieval_returned_no_new_evidence")));
                break;
            }
            assessment = next;
        }
        String stopReason = assessment.sufficient() ? "evidence_sufficient"
            : rounds.size() >= maxRounds ? "max_rounds_reached" : "no_new_evidence";
        return new Result(List.copyOf(evidence), assessment, List.copyOf(rounds), stopReason);
    }

    private List<EvidenceItem> deduplicate(List<EvidenceItem> candidates, List<EvidenceItem> existing) {
        Set<String> seen = new LinkedHashSet<>();
        safe(existing).forEach(item -> seen.add(item.identity()));
        List<EvidenceItem> result = new ArrayList<>();
        safe(candidates).forEach(item -> {
            if (seen.add(item.identity())) result.add(item);
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
        String identity() {
            if (id != null && !id.isBlank()) return id;
            return String.join("|", String.valueOf(sourceId), String.valueOf(locator), String.valueOf(text));
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
