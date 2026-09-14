package com.chatchat.agents.orchestration.analysis.dataset;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Produces deterministic unique dataset ids without colliding with source-provided references. */
public final class DatasetReferenceSequence {
    private final Set<String> reserved;
    private final Set<String> emitted = new LinkedHashSet<>();
    private final Map<String, Integer> occurrences = new LinkedHashMap<>();

    public DatasetReferenceSequence(Collection<String> sourceReferences) {
        reserved = sourceReferences == null ? Set.of()
            : Set.copyOf(sourceReferences.stream()
                .filter(value -> value != null && !value.isBlank()).toList());
    }

    public String next(String sourceReference) {
        String reference = sourceReference == null || sourceReference.isBlank()
            ? "result" : sourceReference;
        int occurrence = occurrences.merge(reference, 1, Integer::sum);
        String candidate = occurrence == 1
            ? reference : reference + "#occurrence-" + occurrence;
        int disambiguator = 1;
        while (emitted.contains(candidate)
            || (occurrence > 1 && reserved.contains(candidate))) {
            candidate = reference + "#occurrence-" + occurrence
                + "#runtime-" + disambiguator++;
        }
        emitted.add(candidate);
        return candidate;
    }
}
