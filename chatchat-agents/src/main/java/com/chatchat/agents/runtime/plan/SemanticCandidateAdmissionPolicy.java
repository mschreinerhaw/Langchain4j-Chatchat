package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kernel policy for admitting model-reviewed candidates from any discovery node.
 *
 * <p>This class knows nothing about assets, templates, databases, or MCP tool names.
 * Protocol adapters provide candidate ids and reviewer decisions; the Runtime owns
 * only the deterministic admission invariants.</p>
 */
final class SemanticCandidateAdmissionPolicy {

    Decision decide(List<String> candidateIds,
                    List<String> selectedIds,
                    List<String> rejectedIds,
                    boolean reviewerUnavailable) {
        Map<String, String> authorized = authorized(candidateIds);
        if (reviewerUnavailable || authorized.isEmpty()) {
            return Decision.undecided();
        }
        Set<String> rejected = normalized(rejectedIds);
        List<String> explicit = project(selectedIds, authorized, rejected);
        if (!explicit.isEmpty()) {
            return new Decision(true, explicit, "runtime_evidence_model_review");
        }
        if (selectedIds != null && !selectedIds.isEmpty() && rejected.isEmpty()) {
            // The reviewer selected only unknown or explicitly rejected ids.
            return Decision.undecided();
        }
        if (authorized.size() == 1 && rejected.isEmpty()) {
            return new Decision(true, List.copyOf(authorized.values()), "runtime_unique_candidate");
        }
        if (!rejected.isEmpty()) {
            List<String> survivors = authorized.entrySet().stream()
                .filter(entry -> !rejected.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
            // Rejection-only review is decisive only if it leaves one or no candidate.
            if (survivors.size() <= 1) {
                return new Decision(true, survivors, "runtime_evidence_model_review");
            }
        }
        return Decision.undecided();
    }

    private Map<String, String> authorized(List<String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value != null && !value.isBlank()) {
                result.putIfAbsent(normalize(value), value);
            }
        }
        return result;
    }

    private List<String> project(List<String> selected,
                                 Map<String, String> authorized,
                                 Set<String> rejected) {
        List<String> result = new ArrayList<>();
        for (String value : selected == null ? List.<String>of() : selected) {
            String normalized = normalize(value);
            String original = authorized.get(normalized);
            if (original != null && !rejected.contains(normalized) && !result.contains(original)) {
                result.add(original);
            }
        }
        return List.copyOf(result);
    }

    private Set<String> normalized(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (value != null && !value.isBlank()) {
                result.add(normalize(value));
            }
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Decision(boolean decided, List<String> selectedIds, String authority) {
        static Decision undecided() {
            return new Decision(false, List.of(), "none");
        }
    }
}
