package com.chatchat.mcpserver.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Language-neutral query segmentation shared by registry ranking and search backends. */
public final class SearchQueryTokenizer {

    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern SCRIPT_RUN = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+|[\\p{L}\\p{N}]+");

    private SearchQueryTokenizer() {
    }

    public static List<String> terms(Object value) {
        if (value == null) {
            return List.of();
        }
        String canonical = Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).trim();
        String normalized = normalize(canonical);
        if (normalized.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        terms.add(canonical);
        terms.add(normalized);
        java.util.regex.Matcher matcher = SCRIPT_RUN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.isBlank() || isSingleLetter(token)) {
                continue;
            }
            if (containsHan(token) && token.length() > 3) {
                for (int index = 0; index < token.length() - 1; index++) {
                    terms.add(token.substring(index, index + 2));
                }
            } else {
                terms.add(token);
            }
        }
        return new ArrayList<>(terms);
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        return SEPARATOR.matcher(normalized).replaceAll(" ").trim();
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static boolean isSingleLetter(String value) {
        return value.codePointCount(0, value.length()) == 1
            && value.codePoints().allMatch(Character::isLetter);
    }
}
