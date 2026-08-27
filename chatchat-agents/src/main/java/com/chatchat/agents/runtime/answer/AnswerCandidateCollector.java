package com.chatchat.agents.runtime.answer;

import com.chatchat.agents.protocol.AgentProtocolCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Request-scoped registration channel for intermediate answer candidates.
 *
 * <p>Any Agent Runtime component may register a user-facing answer produced by
 * one of its stages. Candidates are transient: the finalizer drains and removes
 * them before persistent runtime metadata is returned.</p>
 */
public final class AnswerCandidateCollector {

    public static final String CONTRACT_VERSION = AgentProtocolCatalog.RUNTIME_ANSWER_CANDIDATE;
    public static final String FINAL_SYNTHESIS = "final_synthesis";
    public static final String FACT_GROUNDING_REWRITE = "fact_grounding_rewrite";
    public static final String STRUCTURED_EVIDENCE_MERGE = "structured_evidence_merge";

    private static final String INTERNAL_KEY = "__runtimeAnswerCandidates";

    public void register(Map<String, Object> runtimeMetadata,
                         String stage,
                         String answer) {
        register(runtimeMetadata, stage, answer, List.of(), Map.of());
    }

    public void register(Map<String, Object> runtimeMetadata,
                         String stage,
                         String answer,
                         List<String> evidenceRefs,
                         Map<String, Object> attributes) {
        if (runtimeMetadata == null || answer == null || answer.isBlank()) {
            return;
        }
        String normalizedStage = normalizedStage(stage);
        String normalizedAnswer = normalize(answer);
        synchronized (runtimeMetadata) {
            List<Candidate> candidates = mutableCandidates(runtimeMetadata.get(INTERNAL_KEY));
            boolean duplicate = candidates.stream()
                .anyMatch(candidate -> normalize(candidate.answer()).equals(normalizedAnswer));
            if (duplicate) {
                return;
            }
            candidates.add(new Candidate(
                normalizedStage + "_" + (candidates.size() + 1),
                normalizedStage,
                answer,
                distinctStrings(evidenceRefs),
                attributes == null ? Map.of() : Map.copyOf(attributes)
            ));
            runtimeMetadata.put(INTERNAL_KEY, List.copyOf(candidates));
        }
    }

    public List<Candidate> drain(Map<String, Object> runtimeMetadata) {
        if (runtimeMetadata == null) {
            return List.of();
        }
        synchronized (runtimeMetadata) {
            Object raw = runtimeMetadata.remove(INTERNAL_KEY);
            return List.copyOf(mutableCandidates(raw));
        }
    }

    public boolean hasCandidates(Map<String, Object> runtimeMetadata) {
        if (runtimeMetadata == null) {
            return false;
        }
        synchronized (runtimeMetadata) {
            return !mutableCandidates(runtimeMetadata.get(INTERNAL_KEY)).isEmpty();
        }
    }

    private List<Candidate> mutableCandidates(Object raw) {
        if (!(raw instanceof Iterable<?> values)) {
            return new ArrayList<>();
        }
        List<Candidate> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Candidate candidate) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<String> distinctStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    private String normalizedStage(String stage) {
        String value = stage == null || stage.isBlank() ? "runtime_stage" : stage.trim();
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record Candidate(
        String id,
        String stage,
        String answer,
        List<String> evidenceRefs,
        Map<String, Object> attributes
    ) {
        public Candidate {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            attributes = attributes == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(attributes));
        }
    }
}
