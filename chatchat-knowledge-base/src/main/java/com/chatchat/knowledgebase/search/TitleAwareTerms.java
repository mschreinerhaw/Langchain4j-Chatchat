package com.chatchat.knowledgebase.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TitleAwareTerms {

    private static final int MIN_CJK_FRAGMENT_LENGTH = 5;
    private static final int MAX_CJK_FRAGMENT_LENGTH = 12;
    private static final int MAX_CJK_FRAGMENTS_PER_VALUE = 160;
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(?<!\\d)(\\d{4})[-_./年]?(\\d{1,2})(?:[-_./月]?(\\d{1,2}))?"
    );

    private TitleAwareTerms() {
    }

    static List<String> extract(SearchTokenizer tokenizer, String... values) {
        if (tokenizer == null || values == null || values.length == 0) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String value : values) {
            addValueTerms(tokenizer, terms, value);
        }
        return new ArrayList<>(terms);
    }

    private static void addValueTerms(SearchTokenizer tokenizer, Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        addTokenized(tokenizer, terms, value);
        addTokenized(tokenizer, terms, fileNamePart(value));
        String withoutExtension = stripExtension(fileNamePart(value));
        addTokenized(tokenizer, terms, withoutExtension);
        addNormalizedPhraseTerms(tokenizer, terms, value);
        addNormalizedPhraseTerms(tokenizer, terms, withoutExtension);
        addDateTerms(terms, value);
    }

    private static void addTokenized(SearchTokenizer tokenizer, Set<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        terms.addAll(tokenizer.tokenize(value));
    }

    private static void addNormalizedPhraseTerms(SearchTokenizer tokenizer, Set<String> terms, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return;
        }
        terms.add(normalized);
        String compact = normalized.replace(" ", "");
        terms.add(compact);
        addCjkFragmentTerms(terms, compact);
        List<String> tokens = tokenizer.searchTokens(normalized);
        for (int size = 2; size <= 3; size++) {
            if (tokens.size() < size) {
                continue;
            }
            for (int i = 0; i <= tokens.size() - size; i++) {
                List<String> window = tokens.subList(i, i + size);
                String spaced = String.join(" ", window);
                terms.add(spaced);
                terms.add(spaced.replace(" ", ""));
            }
        }
    }

    private static void addDateTerms(Set<String> terms, String value) {
        Matcher matcher = DATE_PATTERN.matcher(value);
        while (matcher.find()) {
            String year = matcher.group(1);
            String month = twoDigits(matcher.group(2));
            String day = twoDigits(matcher.group(3));
            if (month.isBlank()) {
                continue;
            }
            terms.add(year);
            terms.add(year + "-" + month);
            terms.add(year + month);
            if (!day.isBlank()) {
                terms.add(year + "-" + month + "-" + day);
                terms.add(year + month + day);
            }
        }
    }

    private static void addCjkFragmentTerms(Set<String> terms, String value) {
        if (value == null || value.length() < MIN_CJK_FRAGMENT_LENGTH || !containsCjk(value)) {
            return;
        }
        int added = 0;
        int maxSize = Math.min(MAX_CJK_FRAGMENT_LENGTH, value.length());
        for (int size = MIN_CJK_FRAGMENT_LENGTH; size <= maxSize; size++) {
            for (int i = 0; i <= value.length() - size; i++) {
                terms.add(value.substring(i, i + size));
                added++;
                if (added >= MAX_CJK_FRAGMENTS_PER_VALUE) {
                    return;
                }
            }
        }
    }

    static boolean containsCjk(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (isCjk(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
            || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
            || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
            || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    private static String fileNamePart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return Path.of(value).getFileName().toString();
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String stripExtension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int dot = value.lastIndexOf('.');
        return dot <= 0 ? value : value.substring(0, dot);
    }

    static String normalize(String value) {
        return value == null ? "" : value
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String twoDigits(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() == 1 ? "0" + trimmed : trimmed;
    }
}
