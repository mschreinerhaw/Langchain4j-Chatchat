package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.search.FeatureHashVectorizer;
import com.chatchat.mcpserver.search.LuceneMcpSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PythonTemplateSearchService {
    private static final String ASSET_TYPE = "python_template";

    private final PythonTemplateAssetRepository templates;
    private final LuceneMcpSearchService searchService;

    public List<LuceneMcpSearchService.TemplateDoc> publishedDocuments() {
        return templates.findByStatus("PUBLISHED").stream().map(this::document).toList();
    }

    public void upsert(PythonTemplate template) {
        if (template != null && "PUBLISHED".equals(template.getStatus())) {
            searchService.upsertTemplates(List.of(document(template)));
        }
    }

    public List<SearchResult> search(String query, String categoryId, int requestedLimit) {
        String intent = text(query);
        String category = text(categoryId);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        List<PythonTemplate> allPublished = templates.findByStatus("PUBLISHED");
        List<PythonTemplate> published = allPublished.stream()
                .filter(template -> category.isBlank() || category.equals(text(template.getCategoryId())))
                .toList();
        Map<String, PythonTemplate> authoritative = new LinkedHashMap<>();
        published.forEach(template -> authoritative.put(template.getId(), template));

        // The database is authoritative; the index is refreshed before querying so
        // a general template-index rebuild cannot leave Python capabilities missing.
        searchService.upsertTemplates(allPublished.stream().map(this::document).toList());
        List<SearchResult> indexed = searchService.searchTemplates(
                        new LuceneMcpSearchService.TemplateSearchRequest(ASSET_TYPE, null, intent, limit, category.isBlank() ? null : category))
                .stream()
                .filter(hit -> authoritative.containsKey(hit.id()))
                .limit(limit)
                .map(hit -> result(authoritative.get(hit.id()), hit.score(),
                        hit.reasons() == null || hit.reasons().isEmpty() ? "BM25 + KNN" : String.join(" + ", hit.reasons())))
                .toList();
        if (!indexed.isEmpty()) return indexed;
        if (intent.isBlank())
            return published.stream().limit(limit).map(template -> result(template, 1F, "DATABASE_FALLBACK")).toList();

        String normalized = intent.toLowerCase(Locale.ROOT);
        return published.stream()
                .map(template -> Map.entry(template, lexicalScore(template, normalized)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<PythonTemplate, Float>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry -> result(entry.getKey(), entry.getValue(), "DATABASE_FALLBACK"))
                .toList();
    }

    private LuceneMcpSearchService.TemplateDoc document(PythonTemplate template) {
        String semantic = String.join(" ", text(template.getScriptFileName()), text(template.getTemplateName()), text(template.getScenario()),
                text(template.getDescription()), text(template.getKeywords()), text(template.getDomain()),
                text(template.getInputSchemaJson()), text(template.getOutputSchemaJson()));
        List<String> tags = List.of(text(template.getKeywords()), text(template.getDomain())).stream()
                .filter(value -> !value.isBlank()).toList();
        return new LuceneMcpSearchService.TemplateDoc(template.getId(), ASSET_TYPE,
                text(template.getTemplateName()), text(template.getDescription()), text(template.getCategoryId()), null,
                text(template.getScenario()), "LOW", tags, "python_asset", text(template.getToolName()),
                text(template.getDescription()), text(template.getScenario()), text(template.getDomain()),
                text(template.getScenario()), tags, List.of(), List.of(), FeatureHashVectorizer.vectorize(semantic, 256));
    }

    private SearchResult result(PythonTemplate template, float score, String channel) {
        return new SearchResult(template.getId(), template.getTemplateName(), template.getToolName(),
                template.getScenario(), template.getDescription(), template.getEnvironmentId(), score, channel);
    }

    private float lexicalScore(PythonTemplate template, String query) {
        String searchable = String.join(" ", text(template.getScriptFileName()), text(template.getTemplateName()), text(template.getToolName()),
                text(template.getScenario()), text(template.getDescription()), text(template.getKeywords()),
                text(template.getDomain())).toLowerCase(Locale.ROOT);
        if (searchable.contains(query)) return 1F;
        float score = 0F;
        for (String token : query.split("\\s+")) if (!token.isBlank() && searchable.contains(token)) score += 0.2F;
        return score;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record SearchResult(String id, String templateName, String toolName, String scenario,
                               String description, String environmentId, float score, String channel) {
    }
}
