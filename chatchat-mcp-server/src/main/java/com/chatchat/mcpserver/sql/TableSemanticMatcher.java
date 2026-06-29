package com.chatchat.mcpserver.sql;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class TableSemanticMatcher {

    public double similarity(String requestedTable, String candidateTable) {
        String requested = normalize(requestedTable);
        String candidate = normalize(candidateTable);
        if (requested == null || candidate == null) {
            return 0.0;
        }
        if (requested.equals(candidate)) {
            return 1.0;
        }
        String compactRequested = requested.replace("_", "");
        String compactCandidate = candidate.replace("_", "");
        if (compactRequested.equals(compactCandidate)) {
            return 0.8;
        }
        if (candidate.startsWith(requested + "_") || candidate.startsWith(requested)) {
            return 0.6;
        }
        if (requested.startsWith(candidate + "_") || requested.startsWith(candidate)) {
            return 0.5;
        }
        return levenshteinSimilarity(requested, candidate) >= 0.72 ? 0.4 : 0.0;
    }

    public String matchKind(String requestedTable, String candidateTable) {
        double value = similarity(requestedTable, candidateTable);
        if (value >= 1.0) {
            return "exact";
        }
        if (value >= 0.8) {
            return "underscore";
        }
        if (value >= 0.5) {
            return "prefix";
        }
        if (value > 0.0) {
            return "fuzzy";
        }
        return "none";
    }

    private double levenshteinSimilarity(String left, String right) {
        int distance = levenshtein(left, right);
        int max = Math.max(left.length(), right.length());
        return max == 0 ? 1.0 : 1.0 - ((double) distance / max);
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
