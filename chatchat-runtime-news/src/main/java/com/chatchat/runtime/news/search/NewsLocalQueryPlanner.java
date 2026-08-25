package com.chatchat.runtime.news.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds bounded, topic-neutral local-news query variants from one user query. */
public final class NewsLocalQueryPlanner {
    public static final String STRATEGY = "original_plus_keyword_fanout_v1";
    private static final Pattern CONJUNCTION = Pattern.compile("(?i)(?:以及|并且|或者|和|与|及|或|\\band\\b|\\bor\\b)");
    private static final Pattern EDGE_NOISE = Pattern.compile(
        "(?i)^(?:请|帮我|帮忙|搜索|检索|查找|查询|看看|了解|关于|有关|最新|最近|近期|今日|今天|"
            + "search|find|show|latest|recent|today|about|news|information|info)+|"
            + "(?:的|相关|新闻|资讯|信息|报道|动态|消息|news|information|info)$");
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "of", "for", "to", "in", "on", "at", "from", "by", "with",
        "latest", "recent", "today", "news", "information", "info", "search", "find", "show",
        "请", "关于", "有关", "最新", "最近", "近期", "今日", "今天", "新闻", "资讯", "信息", "报道", "动态", "消息"
    );

    private NewsLocalQueryPlanner() {
    }

    public static QueryPlan plan(String query, Object explicitTerms, int maximumTerms) {
        String original = normalize(query);
        int limit = Math.max(1, Math.min(maximumTerms, 16));
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        addExplicit(keywords, explicitTerms, limit);
        if (keywords.isEmpty()) addAutomatic(keywords, original, limit);
        keywords.removeIf(term -> term.equalsIgnoreCase(original));

        List<String> boundedKeywords = keywords.stream().limit(limit).toList();
        List<String> queries = new ArrayList<>();
        if (!original.isBlank()) queries.add(original);
        queries.addAll(boundedKeywords);
        return new QueryPlan(original, boundedKeywords, List.copyOf(new LinkedHashSet<>(queries)));
    }

    private static void addExplicit(Set<String> target, Object raw, int limit) {
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                addKeyword(target, value, limit);
                if (target.size() >= limit) break;
            }
            return;
        }
        if (raw != null) {
            for (String value : String.valueOf(raw).split("[,，、;；|\\n]+")) {
                addKeyword(target, value, limit);
                if (target.size() >= limit) break;
            }
        }
    }

    private static void addAutomatic(Set<String> target, String query, int limit) {
        if (query.isBlank()) return;
        for (String clause : query.split("[,，、;；|\\n]+")) {
            for (String part : CONJUNCTION.split(clause)) {
                String cleaned = clean(part);
                if (cleaned.isBlank()) continue;
                String[] words = cleaned.split("\\s+");
                if (words.length > 1) {
                    for (String word : words) addKeyword(target, word, limit);
                } else {
                    addKeyword(target, cleaned, limit);
                }
                if (target.size() >= limit) return;
            }
        }
    }

    private static void addKeyword(Set<String> target, Object raw, int limit) {
        if (target.size() >= limit || raw == null) return;
        String value = clean(String.valueOf(raw));
        int length = value.codePointCount(0, value.length());
        if (length < 2 || length > 64 || STOP_WORDS.contains(value.toLowerCase(Locale.ROOT))) return;
        target.add(value);
    }

    private static String clean(String raw) {
        String value = normalize(raw).replaceAll("^[\\p{Punct}\\p{S}]+|[\\p{Punct}\\p{S}]+$", "");
        String previous;
        do {
            previous = value;
            value = EDGE_NOISE.matcher(value).replaceAll("").trim();
        } while (!value.equals(previous) && !value.isBlank());
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("\\s+", " ").trim();
    }

    public record QueryPlan(String originalQuery, List<String> keywords, List<String> queries) {
    }
}
