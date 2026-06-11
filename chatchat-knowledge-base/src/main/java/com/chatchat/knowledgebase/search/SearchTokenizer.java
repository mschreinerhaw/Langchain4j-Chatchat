package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class SearchTokenizer {

    private static final Set<String> SEARCH_STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "can", "for", "from", "how", "in", "is",
        "content", "document", "file", "find", "information", "it", "note", "notes", "of",
        "on", "or", "please", "related", "relevant", "report", "search", "show", "summary",
        "tell", "that", "the", "this", "to", "what", "when", "where", "which", "who", "why",
        "with",
        "\u4e00\u4e0b", "\u4e00\u4e2a", "\u4e00\u4e9b", "\u4e0a\u8ff0", "\u4e0b\u8f7d",
        "\u4e2d\u7684", "\u4e3a\u4ec0\u4e48", "\u4e86\u89e3", "\u4ec0\u4e48", "\u4ecb\u7ecd",
        "\u4eca\u5929", "\u4ee5\u53ca", "\u4f60\u4eec", "\u4f7f\u7528", "\u5173\u4e8e",
        "\u5185\u5bb9", "\u5206\u6790", "\u53ef\u4ee5", "\u54ea\u4e9b", "\u54ea\u4e2a",
        "\u56de\u7b54", "\u56fe\u7247", "\u5982\u4f55", "\u5e2e\u6211", "\u5f53\u524d",
        "\u600e\u4e48", "\u6211\u4eec", "\u6240\u6709", "\u62a5\u544a", "\u6587\u4ef6",
        "\u6587\u6863", "\u662f\u5426", "\u6700\u65b0", "\u67e5\u770b", "\u68c0\u7d22",
        "\u6bd4\u8f83", "\u7136\u540e", "\u76f8\u5173", "\u7ed3\u679c", "\u8bf7\u95ee",
        "\u8fd9\u4e2a", "\u8fd9\u4e9b", "\u8fdb\u884c", "\u9700\u8981", "\u9879\u76ee"
    );

    /**
     * Converts the value to kenize.
     *
     * @param text the text value
     * @return the converted kenize
     */
    List<String> tokenize(String text) {
        return new ArrayList<>(new LinkedHashSet<>(tokenizeOccurrences(text)));
    }

    /**
     * Searches the tokens.
     *
     * @param text the text value
     * @return the operation result
     */
    List<String> searchTokens(String text) {
        return tokenize(text).stream()
            .filter(token -> !isSearchNoiseToken(token))
            .toList();
    }

    /**
     * Converts the value to kenize occurrences.
     *
     * @param text the text value
     * @return the converted kenize occurrences
     */
    List<String> tokenizeOccurrences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
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

        return tokens;
    }

    /**
     * Normalizes the terms.
     *
     * @param values the values value
     * @return the operation result
     */
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

    /**
     * Performs the split filter operation.
     *
     * @param value the value value
     * @return the operation result
     */
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

    /**
     * Normalizes the exact term.
     *
     * @param value the value value
     * @return the operation result
     */
    String normalizeExactTerm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns whether is search noise token.
     *
     * @param token the token value
     * @return whether the condition is satisfied
     */
    boolean isSearchNoiseToken(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        String normalized = normalizeExactTerm(token);
        if (SEARCH_STOP_WORDS.contains(normalized)) {
            return true;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return normalized.length() < 4;
        }
        return normalized.length() <= 1;
    }

    /**
     * Performs the flush operation.
     *
     * @param buffer the buffer value
     * @param mode the mode value
     * @param tokens the tokens value
     */
    private void flush(StringBuilder buffer, CharacterMode mode, List<String> tokens) {
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

    /**
     * Adds the cjk ngrams.
     *
     * @param token the token value
     * @param tokens the tokens value
     */
    private void addCjkNgrams(String token, List<String> tokens) {
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

    /**
     * Performs the mode of operation.
     *
     * @param ch the ch value
     * @return the operation result
     */
    private CharacterMode modeOf(char ch) {
        if (isCjk(ch)) {
            return CharacterMode.CJK;
        }
        if (Character.isLetterOrDigit(ch)) {
            return CharacterMode.LATIN;
        }
        return CharacterMode.OTHER;
    }

    /**
     * Returns whether is cjk.
     *
     * @param ch the ch value
     * @return whether the condition is satisfied
     */
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
