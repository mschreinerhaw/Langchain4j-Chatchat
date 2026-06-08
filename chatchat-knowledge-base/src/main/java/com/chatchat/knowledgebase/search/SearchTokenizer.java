package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class SearchTokenizer {

    List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder buffer = new StringBuilder();
        CharacterMode currentMode = CharacterMode.OTHER;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            CharacterMode mode = modeOf(ch);
            if (mode == CharacterMode.OTHER) {
                flush(buffer, currentMode, tokens);
                currentMode = CharacterMode.OTHER;
                continue;
            }
            if (buffer.length() > 0 && mode != currentMode) {
                flush(buffer, currentMode, tokens);
                buffer.setLength(0);
            }
            buffer.append(Character.toLowerCase(ch));
            currentMode = mode;
        }
        flush(buffer, currentMode, tokens);

        return new ArrayList<>(tokens);
    }

    List<String> normalizeTerms(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String value : values) {
            terms.addAll(tokenize(value));
        }
        return new ArrayList<>(terms);
    }

    List<String> splitFilter(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("[,，;；\\s]+");
        Set<String> terms = new LinkedHashSet<>();
        for (String part : parts) {
            String normalized = normalizeExactTerm(part);
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
        }
        return new ArrayList<>(terms);
    }

    String normalizeExactTerm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void flush(StringBuilder buffer, CharacterMode mode, Set<String> tokens) {
        if (buffer.length() == 0) {
            return;
        }
        String token = buffer.toString().trim().toLowerCase(Locale.ROOT);
        if (token.length() < 2) {
            return;
        }
        tokens.add(token);
        if (mode == CharacterMode.CJK) {
            addCjkNgrams(token, tokens);
        }
    }

    private void addCjkNgrams(String token, Set<String> tokens) {
        int length = token.length();
        for (int size = 2; size <= 4; size++) {
            if (length < size) {
                continue;
            }
            for (int i = 0; i <= length - size; i++) {
                tokens.add(token.substring(i, i + size));
            }
        }
    }

    private CharacterMode modeOf(char ch) {
        if (isCjk(ch)) {
            return CharacterMode.CJK;
        }
        if (Character.isLetterOrDigit(ch)) {
            return CharacterMode.LATIN;
        }
        return CharacterMode.OTHER;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
            || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
            || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
            || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    private enum CharacterMode {
        LATIN,
        CJK,
        OTHER
    }
}
