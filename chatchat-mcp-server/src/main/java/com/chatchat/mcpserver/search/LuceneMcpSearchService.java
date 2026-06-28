package com.chatchat.mcpserver.search;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LuceneMcpSearchService {

    private final Map<String, Map<String, AssetDoc>> assetDocsByType = new LinkedHashMap<>();
    private final Map<String, Map<String, TemplateDoc>> templateDocsByType = new LinkedHashMap<>();

    public boolean enabled() {
        return true;
    }

    public synchronized void replaceAssets(List<AssetDoc> docs) {
        assetDocsByType.clear();
        for (AssetDoc doc : docs == null ? List.<AssetDoc>of() : docs) {
            if (doc != null && text(doc.id()) != null) {
                assetDocsByType
                    .computeIfAbsent(indexType(doc.assetType()), ignored -> new LinkedHashMap<>())
                    .put(doc.id(), doc);
            }
        }
    }

    public synchronized void replaceTemplates(List<TemplateDoc> docs) {
        templateDocsByType.clear();
        upsertTemplates(docs);
    }

    public synchronized void upsertTemplates(List<TemplateDoc> docs) {
        for (TemplateDoc doc : docs == null ? List.<TemplateDoc>of() : docs) {
            if (doc != null && text(doc.id()) != null) {
                templateDocsByType
                    .computeIfAbsent(indexType(doc.assetType()), ignored -> new LinkedHashMap<>())
                    .put(doc.id(), doc);
            }
        }
    }

    public synchronized List<SearchHit> searchAssets(AssetSearchRequest request) {
        return scoreAssets(assetIndex(request == null ? null : request.assetType()), request);
    }

    public List<SearchHit> searchTemplates(List<TemplateDoc> docs, TemplateSearchRequest request) {
        List<TemplateDoc> universe = docs == null || docs.isEmpty()
            ? synchronizedTemplateDocs(request == null ? null : request.assetType())
            : docs;
        return scoreTemplates(universe, request);
    }

    private synchronized List<TemplateDoc> synchronizedTemplateDocs(String assetType) {
        return templateIndex(assetType);
    }

    private List<AssetDoc> assetIndex(String assetType) {
        String type = normalize(assetType);
        if (type != null) {
            return new ArrayList<>(assetDocsByType.getOrDefault(type, Map.of()).values());
        }
        return assetDocsByType.values().stream()
            .flatMap(values -> values.values().stream())
            .toList();
    }

    private List<TemplateDoc> templateIndex(String assetType) {
        String type = normalize(assetType);
        if (type != null) {
            return new ArrayList<>(templateDocsByType.getOrDefault(type, Map.of()).values());
        }
        return templateDocsByType.values().stream()
            .flatMap(values -> values.values().stream())
            .toList();
    }

    private List<SearchHit> scoreAssets(List<AssetDoc> docs, AssetSearchRequest request) {
        List<String> queryTokens = assetQueryTokens(request);
        int limit = limit(request == null ? 20 : request.limit());
        return docs.stream()
            .filter(doc -> request == null || matchesType(request.assetType(), doc.assetType()))
            .map(doc -> hit(doc.id(), "asset", queryTokens, assetText(doc)))
            .filter(hit -> hit.score() > 0 || queryTokens.isEmpty())
            .sorted(hitComparator())
            .limit(limit)
            .toList();
    }

    private List<SearchHit> scoreTemplates(List<TemplateDoc> docs, TemplateSearchRequest request) {
        List<String> queryTokens = templateQueryTokens(request);
        int limit = limit(request == null ? 20 : request.limit());
        return docs.stream()
            .filter(doc -> request == null || matchesType(request.assetType(), doc.assetType()))
            .filter(doc -> request == null || matchesDbType(request.dbType(), doc.dbType()))
            .map(doc -> hit(doc.id(), "template", queryTokens, templateText(doc)))
            .filter(hit -> hit.score() > 0 || queryTokens.isEmpty())
            .sorted(hitComparator())
            .limit(limit)
            .toList();
    }

    private SearchHit hit(String id, String kind, List<String> queryTokens, String text) {
        Set<String> indexed = tokens(text);
        if (queryTokens.isEmpty()) {
            return new SearchHit(id, kind, 1.0f, List.of("match_all"));
        }
        float score = 0.0f;
        List<String> reasons = new ArrayList<>();
        for (String token : queryTokens) {
            if (indexed.contains(token)) {
                score += 2.0f;
                reasons.add("token:" + token);
            } else if (text != null && token.length() >= 2 && normalize(text).contains(token)) {
                score += 1.0f;
                reasons.add("substring:" + token);
            }
        }
        return new SearchHit(id, kind, score, reasons.stream().limit(6).toList());
    }

    private Comparator<SearchHit> hitComparator() {
        return Comparator
            .<SearchHit>comparingDouble(hit -> -hit.score())
            .thenComparing(SearchHit::id);
    }

    private List<String> assetQueryTokens(AssetSearchRequest request) {
        if (request == null) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, request.name());
        addTokens(tokens, request.env());
        addTokens(tokens, request.dbType());
        if (request.contextTokens() != null) {
            request.contextTokens().forEach(value -> addTokens(tokens, value));
        }
        return tokens.stream().toList();
    }

    private List<String> templateQueryTokens(TemplateSearchRequest request) {
        if (request == null || text(request.keyword()) == null) {
            return List.of();
        }
        return tokens(request.keyword()).stream().toList();
    }

    private String assetText(AssetDoc doc) {
        return String.join(" ",
            textOrEmpty(doc.id()),
            textOrEmpty(doc.assetType()),
            textOrEmpty(doc.name()),
            textOrEmpty(doc.displayName()),
            textOrEmpty(doc.toolName()),
            textOrEmpty(doc.env()),
            textOrEmpty(doc.dbType()),
            String.join(" ", doc.labels() == null ? List.of() : doc.labels()),
            textOrEmpty(doc.source())
        );
    }

    private String templateText(TemplateDoc doc) {
        return String.join(" ",
            textOrEmpty(doc.id()),
            textOrEmpty(doc.assetType()),
            textOrEmpty(doc.title()),
            textOrEmpty(doc.description()),
            textOrEmpty(doc.category()),
            textOrEmpty(doc.dbType()),
            textOrEmpty(doc.intentText()),
            textOrEmpty(doc.riskLevel()),
            String.join(" ", doc.signals() == null ? List.of() : doc.signals()),
            textOrEmpty(doc.source())
        );
    }

    private Set<String> tokens(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addTokens(result, value);
        return result;
    }

    private void addTokens(Set<String> tokens, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return;
        }
        tokens.add(normalized);
        for (String token : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        addBilingualSynonyms(tokens, normalized);
    }

    private void addBilingualSynonyms(Set<String> tokens, String text) {
        addSynonyms(tokens, text, List.of("status", "health", "instance", "\u72b6\u6001", "\u5065\u5eb7", "\u5b9e\u4f8b", "\u6570\u636e\u5e93\u72b6\u6001"));
        addSynonyms(tokens, text, List.of("performance", "slow", "latency", "cpu", "\u6027\u80fd", "\u6162", "\u5361\u987f", "\u6162\u67e5\u8be2"));
        addSynonyms(tokens, text, List.of("lock", "blocking", "deadlock", "wait", "\u9501", "\u963b\u585e", "\u7b49\u5f85", "\u6b7b\u9501"));
        addSynonyms(tokens, text, List.of("storage", "size", "space", "capacity", "\u7a7a\u95f4", "\u5bb9\u91cf", "\u5927\u5c0f", "\u5b58\u50a8"));
        addSynonyms(tokens, text, List.of("metadata", "schema", "column", "field", "\u5143\u6570\u636e", "\u8868\u7ed3\u6784", "\u5b57\u6bb5", "\u5217"));
        addSynonyms(tokens, text, List.of("connection", "session", "processlist", "connections", "\u8fde\u63a5", "\u4f1a\u8bdd", "\u8fde\u63a5\u6570"));
    }

    private void addSynonyms(Set<String> tokens, String text, List<String> synonyms) {
        boolean matched = synonyms.stream().anyMatch(item -> {
            String normalized = normalize(item);
            return normalized != null && text.contains(normalized);
        });
        if (matched) {
            synonyms.forEach(item -> {
                String normalized = normalize(item);
                if (normalized != null) {
                    tokens.add(normalized);
                }
            });
        }
    }

    private boolean matchesType(String requested, String actual) {
        String left = normalize(requested);
        return left == null || left.equals(normalize(actual));
    }

    private boolean matchesDbType(String requested, String actual) {
        String left = normalize(requested);
        String right = normalize(actual);
        return left == null || "generic".equals(right) || left.equals(right);
    }

    private String indexType(String assetType) {
        String normalized = normalize(assetType);
        return normalized == null ? "_unknown" : normalized;
    }

    private int limit(int value) {
        return Math.max(1, Math.min(100, value <= 0 ? 20 : value));
    }

    private String normalize(String value) {
        String text = text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String textOrEmpty(String value) {
        String text = text(value);
        return text == null ? "" : text;
    }

    public record SearchHit(String id, String kind, float score, List<String> reasons) {
    }

    public record TemplateSearchRequest(String assetType, String dbType, String keyword, int limit) {
    }

    public record TemplateDoc(String id,
                              String assetType,
                              String title,
                              String description,
                              String category,
                              String dbType,
                              String intentText,
                              String riskLevel,
                              List<String> signals,
                              String source) {
    }

    public record AssetSearchRequest(String assetType,
                                     String name,
                                     String env,
                                     String dbType,
                                     List<String> contextTokens,
                                     int limit) {
    }

    public record AssetDoc(String id,
                           String assetType,
                           String name,
                           String displayName,
                           String toolName,
                           String env,
                           String dbType,
                           List<String> labels,
                           String source) {
    }
}
