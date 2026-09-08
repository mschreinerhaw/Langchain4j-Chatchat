package com.chatchat.mcpserver.templatepublication.retrieval;

import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Performs bounded candidate recall strictly inside a persisted child-tool binding. */
public final class BoundTemplateCandidateRetriever {

    private static final List<String> SIGNAL_KEYS = List.of(
        "query", "intent", "goal", "intentZh", "intentEn", "bilingualIntent",
        "intentAliases", "queryTerms", "keywords", "retrievalSignals");

    public Recall recall(List<TemplateAssetCatalogService.TemplateAsset> assets,
                         Set<String> allowedTemplateIds,
                         Map<String, Object> arguments,
                         int limit,
                         String policyVersion) {
        List<String> signals = signals(arguments);
        String fingerprint = fingerprint(signals, policyVersion);
        int offset = cursorOffset(arguments == null ? null : arguments.get("cursor"), fingerprint);
        Set<String> allowed = allowedTemplateIds == null ? Set.of() : allowedTemplateIds;
        List<Scored> ranked = (assets == null ? List.<TemplateAssetCatalogService.TemplateAsset>of() : assets)
            .stream()
            .filter(asset -> allowed.contains(asset.templateId()))
            .map(asset -> new Scored(asset, score(asset, signals)))
            .sorted(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(item -> item.asset().templateId()))
            .toList();
        int start = Math.min(Math.max(0, offset), ranked.size());
        int end = Math.min(ranked.size(), start + Math.max(1, limit));
        List<TemplateAssetCatalogService.TemplateAsset> page = ranked.subList(start, end).stream()
            .map(Scored::asset).toList();
        return new Recall(page, ranked.size(), start, end < ranked.size(),
            end < ranked.size() ? cursor(end, fingerprint) : null, signals.size());
    }

    private double score(TemplateAssetCatalogService.TemplateAsset asset, List<String> signals) {
        if (signals.isEmpty()) return 0.0;
        String text = String.join(" ", List.of(
            safe(asset.templateId()), safe(asset.title()), safe(asset.description()),
            safe(asset.category()), safe(asset.businessCategoryCode()),
            safe(asset.businessCategoryName()), String.valueOf(asset.parameterSchema())))
            .toLowerCase(Locale.ROOT);
        Set<String> documentTerms = terms(text);
        double score = 0.0;
        for (String signal : signals) {
            String normalized = signal.toLowerCase(Locale.ROOT);
            if (text.contains(normalized)) score += 4.0;
            Set<String> queryTerms = terms(normalized);
            if (!queryTerms.isEmpty()) {
                long matches = queryTerms.stream().filter(documentTerms::contains).count();
                score += (double) matches / queryTerms.size();
            }
        }
        return score;
    }

    private List<String> signals(Map<String, Object> arguments) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        collectSignals(arguments, values);
        return List.copyOf(values);
    }

    private void collectSignals(Map<String, Object> source, Set<String> target) {
        if (source == null) return;
        for (String key : SIGNAL_KEYS) collect(source.get(key), target);
        Object filters = source.get("filters");
        if (filters instanceof Map<?, ?> map) collectSignals(cast(map), target);
    }

    private void collect(Object value, Set<String> target) {
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collect(item, target));
            return;
        }
        if (value == null) return;
        String normalized = String.valueOf(value).trim();
        if (!normalized.isEmpty() && normalized.length() <= 512) target.add(normalized);
    }

    private Set<String> terms(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value == null) return result;
        for (String token : value.split("[^\\p{L}\\p{N}_]+")) {
            if (!token.isBlank()) result.add(token);
            if (containsHan(token) && token.length() > 1) {
                for (int index = 0; index < token.length() - 1; index++) {
                    result.add(token.substring(index, index + 2));
                }
            }
        }
        return result;
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint ->
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private int cursorOffset(Object rawCursor, String fingerprint) {
        if (rawCursor == null || String.valueOf(rawCursor).isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(String.valueOf(rawCursor)),
                StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2 || !fingerprint.equals(parts[1])) {
                throw new IllegalArgumentException("Template recall cursor does not match the current binding/query");
            }
            return Integer.parseInt(parts[0]);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid bound-template recall cursor", invalid);
        }
    }

    private String cursor(int offset, String fingerprint) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (offset + "|" + fingerprint).getBytes(StandardCharsets.UTF_8));
    }

    private String fingerprint(List<String> signals, String policyVersion) {
        try {
            String value = safe(policyVersion) + "|" + String.join("|", signals);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint bound-template recall", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record Scored(TemplateAssetCatalogService.TemplateAsset asset, double score) { }

    public record Recall(List<TemplateAssetCatalogService.TemplateAsset> templates,
                         int candidateUniverseCount, int offset, boolean hasMore,
                         String nextCursor, int retrievalSignalCount) { }
}
