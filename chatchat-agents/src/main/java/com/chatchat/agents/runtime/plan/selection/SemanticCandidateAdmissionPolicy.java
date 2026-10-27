package com.chatchat.agents.runtime.plan.selection;

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
        if(selectedIds!=null&&selectedIds.stream().anyMatch(id->!authorized.containsKey(normalize(id))||rejected.contains(normalize(id))))
            return Decision.undecided();
        List<String> explicit = project(selectedIds, authorized, rejected);
        if (selectedIds != null && !selectedIds.isEmpty() && explicit.isEmpty()) return Decision.undecided();
        // Existence/membership only. No singleton, survivor, score or relevance inference.
        return new Decision(true, explicit, "model_selection_validated");
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
