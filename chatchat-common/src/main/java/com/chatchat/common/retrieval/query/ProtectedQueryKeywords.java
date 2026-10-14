package com.chatchat.common.retrieval.query;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Full-input term-frequency/phrase extraction; never prefix truncation. */
public final class ProtectedQueryKeywords {
    private ProtectedQueryKeywords() { }
    private static final Pattern PROTECTED = Pattern.compile(
        "[\\\"“「][^\\\"”」\\r\\n]+[\\\"”」]|'[^'\\r\\n]+'|"
        + "(?<![\\p{L}\\p{N}])(?:[A-Za-z]{0,5}[.:_-]?)?\\d[\\dA-Za-z.:_/-]*(?:%|％|亿元|万元|元|年|月|日|倍|万|亿)?|"
        + "(?:大于|小于|超过|至少|至多|不低于|不高于|>=|<=|>|<)\\s*\\d+(?:\\.\\d+)?(?:%|％|亿元|万元|元|倍|万|亿)?|"
        + "[A-Za-z][A-Za-z0-9]*(?:[_.][A-Za-z0-9]+)+|"
        + "[\\p{IsHan}A-Za-z0-9]{2,40}(?:股份有限公司|有限责任公司|有限公司|集团|公司)");
    private static final Pattern NEGATION = Pattern.compile("(?i)(?:不包括|不包含|排除|不得|禁止|不含|不要|\\bNOT\\b|\\bexcluding\\b|\\bwithout\\b)");
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_./:-]+");
    private static final Set<String> NOISE = Set.of("the", "and", "for", "from", "with", "please", "select", "create", "table", "as", "请", "帮我", "查询", "检索", "分析", "以下");

    public record Extraction(List<String> protectedPhrases, List<String> keywords, String text) { }

    public static List<String> protect(String original, List<String> configured) {
        var protectedTerms = new LinkedHashSet<String>();
        var matcher = PROTECTED.matcher(original);
        while (matcher.find()) protectedTerms.add(matcher.group());
        for (String term : configured) if (term != null && !term.isBlank() && original.contains(term)) protectedTerms.add(term);
        return List.copyOf(protectedTerms);
    }

    public static Extraction extract(String original, QueryOptimizationBudget budget, int tokenBudget,
                                     List<String> modelKeywords) {
        return extract(original, budget, tokenBudget, modelKeywords, List.of());
    }
    public static Extraction extract(String original, QueryOptimizationBudget budget, int tokenBudget,
                                     List<String> modelKeywords, List<String> vocabularyPhrases) {
        if (NEGATION.matcher(original).find()) throw new QueryOptimizationException("QUERY_REQUIRES_STRUCTURED_CONSTRAINTS",
            "Long natural-language negation must be supplied as explicit exclusion filters before compression");
        var vocabulary = new ArrayList<>(budget.getProtectedTerms()); vocabulary.addAll(vocabularyPhrases);
        List<String> protectedTerms = protect(original, vocabulary);
        var selected = new LinkedHashSet<String>(protectedTerms);
        if (selected.stream().anyMatch(term -> term.codePointCount(0, term.length()) > budget.getMaxKeywordCodePoints()))
            throw new QueryOptimizationException("PROTECTED_TERMS_EXCEED_BUDGET", "A protected phrase exceeds the keyword budget; it was not truncated");
        if (selected.size() > budget.getMaxKeywords() || tokens(String.join(" ", selected)) > tokenBudget)
            throw new QueryOptimizationException("PROTECTED_TERMS_EXCEED_BUDGET", "Protected entities and constraints do not fit; provide independent retrieval intents");
        var frequency = new LinkedHashMap<String, Integer>();
        var words = WORD.matcher(Normalizer.normalize(original, Normalizer.Form.NFKC));
        while (words.find()) {
            String word = words.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                // Existing search tokenizers use 2..4-character Han n-grams. Retain phrases too.
                if (word.length() <= 24) add(frequency, word);
                for (int width = 2; width <= 4; width++) for (int i = 0; i + width <= word.length(); i++)
                    add(frequency, word.substring(i, i + width));
            } else add(frequency, word);
        }
        var candidates = new ArrayList<String>();
        // Only verbatim spans may be supplied by a refiner. It cannot invent predicates or aliases.
        if (modelKeywords != null) for (String term : modelKeywords)
            if (term != null && !term.isBlank() && original.contains(term)) candidates.add(term);
        frequency.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()
            .thenComparing(entry -> -entry.getKey().length()).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey).forEach(candidates::add);
        for (String term : candidates) {
            if (selected.size() >= budget.getMaxKeywords()) break;
            if (term.codePointCount(0, term.length()) > budget.getMaxKeywordCodePoints()) continue;
            var next = new LinkedHashSet<>(selected); next.add(term);
            if (tokens(String.join(" ", next)) <= tokenBudget) selected = next;
        }
        if (selected.isEmpty()) throw new QueryOptimizationException("QUERY_EXTRACTION_EMPTY", "No safe query terms fit the execution budget");
        return new Extraction(protectedTerms, List.copyOf(selected), String.join(" ", selected));
    }

    private static void add(Map<String,Integer> frequency, String word) {
        if (word.length() > 1 && !NOISE.contains(word.toLowerCase(Locale.ROOT))) frequency.merge(word, 1, Integer::sum);
    }
    public static int tokens(String text) {
        int count = 0; int asciiLength = 0;
        for (int cp : text.codePoints().toArray()) {
            boolean ascii = cp < 128 && Character.isLetterOrDigit(cp);
            if (ascii) { if (asciiLength++ % QueryOptimizationBudget.DEFAULT_TOKEN_CHAR_BUDGET == 0) count++; }
            else if (Character.isLetterOrDigit(cp)) count++;
            if (!ascii) asciiLength = 0;
        }
        return Math.max(1, count);
    }
}
