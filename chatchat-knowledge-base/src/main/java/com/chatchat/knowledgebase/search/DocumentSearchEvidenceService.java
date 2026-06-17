package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DocumentSearchEvidenceService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 30;

    private final SearchService searchService;
    private final SearchTokenizer tokenizer;
    private final QueryIntentClassifier intentClassifier;
    private final EvidenceContextFormatter contextFormatter;

    public DocumentSearchResult search(DocumentSearchRequest request) {
        String query = requireQuery(request == null ? null : request.query());
        int topK = normalizeTopK(request == null ? null : request.topK());
        DocumentSearchFilters filters = request == null ? null : request.filters();
        List<String> scopedFileIds = request == null ? List.of() : safeList(request.fileIds()).stream()
            .filter(this::hasText)
            .map(String::trim)
            .distinct()
            .toList();
        String fileIds = joinValues(scopedFileIds);
        boolean debug = request != null && Boolean.TRUE.equals(request.debug());
        SearchPermissionContext permissionContext = request == null
            ? SearchPermissionContext.system()
            : SearchPermissionContext.of(request.tenantId(), request.userId(), request.roles());

        if (!scopedFileIds.isEmpty()) {
            return searchScopedDocuments(query, topK, scopedFileIds, filters, debug, permissionContext);
        }

        SearchPage page = searchService.search(
            query,
            filters == null ? null : filters.tag(),
            filters == null ? null : filters.company(),
            filters == null ? null : filters.industry(),
            fileIds,
            1,
            Math.max(topK, DEFAULT_TOP_K),
            permissionContext
        );

        String intent = intentClassifier.classifyName(query);
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        for (SearchResult result : page.results()) {
            if (!matchesFileType(result, filters == null ? null : filters.fileType())) {
                continue;
            }
            List<SearchMatchedChunk> matchedChunks = result.matchedChunks();
            if (matchedChunks == null || matchedChunks.isEmpty()) {
                addSummaryEvidence(chunks, result, query, intent, debug, filters);
            } else {
                for (SearchMatchedChunk chunk : matchedChunks) {
                    if (!matchesChunkType(chunk, filters == null ? null : filters.chunkType())) {
                        continue;
                    }
                    chunks.add(toEvidence(result, chunk, query, intent, debug));
                    if (chunks.size() >= topK) {
                        return contextFormatter.toSearchResult(query, intent, chunks);
                    }
                }
            }
            if (chunks.size() >= topK) {
                break;
            }
        }
        return contextFormatter.toSearchResult(query, intent, chunks);
    }

    private DocumentSearchResult searchScopedDocuments(String query,
                                                       int topK,
                                                       List<String> fileIds,
                                                       DocumentSearchFilters filters,
                                                       boolean debug,
                                                       SearchPermissionContext permissionContext) {
        String intent = intentClassifier.classifyName(query);
        List<String> queryTokens = tokenizer.searchTokens(query);
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        for (String fileId : fileIds) {
            searchService.get(fileId, permissionContext)
                .filter(document -> matchesDocumentFilters(document, filters))
                .ifPresent(document -> chunks.addAll(toScopedEvidence(document, query, queryTokens, intent, debug, topK)));
            if (chunks.size() >= topK) {
                break;
            }
        }
        List<DocumentEvidenceChunk> ranked = chunks.stream()
            .sorted(Comparator
                .comparing(DocumentEvidenceChunk::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DocumentEvidenceChunk::fileName, Comparator.nullsLast(String::compareTo)))
            .limit(topK)
            .toList();
        return contextFormatter.toSearchResult(query, intent, ranked);
    }

    private List<DocumentEvidenceChunk> toScopedEvidence(SearchDocument document,
                                                         String query,
                                                         List<String> queryTokens,
                                                         String intent,
                                                         boolean debug,
                                                         int topK) {
        String content = firstNonBlank(document.getContent(), "");
        if (!hasText(content)) {
            return List.of();
        }
        List<ScopedExcerpt> excerpts = scopedExcerpts(content, queryTokens, Math.min(Math.max(1, topK), 3));
        List<DocumentEvidenceChunk> chunks = new ArrayList<>();
        for (int i = 0; i < excerpts.size(); i++) {
            ScopedExcerpt excerpt = excerpts.get(i);
            String fileId = firstNonBlank(document.getDocId(), "unknown");
            String fileName = firstNonBlank(document.getFileName(), document.getTitle(), fileId);
            Integer chunkIndex = excerpt.index();
            chunks.add(new DocumentEvidenceChunk(
                refId(fileId, chunkIndex),
                fileId + "_" + chunkIndex,
                fileId,
                fileName,
                "",
                chunkIndex,
                "document",
                excerpt.score(),
                excerpt.text(),
                highlights(query, List.of(), excerpt.text()),
                citation(fileName, "", chunkIndex),
                debug ? new SearchTrace(
                    highlights(query, List.of(), excerpt.text()),
                    intent,
                    List.of(),
                    "scoped document evidence matched"
                ) : null,
                document.getTenantId(),
                document.getUserId(),
                document.getVisibility(),
                safeList(document.getPermissionRoles())
            ));
        }
        return chunks;
    }

    private List<ScopedExcerpt> scopedExcerpts(String content, List<String> queryTokens, int maxChunks) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        List<String> tokens = safeList(queryTokens).stream()
            .filter(this::hasText)
            .map(token -> token.toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        int window = 1200;
        int overlap = 180;
        List<ScopedExcerpt> excerpts = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < content.length(); start += Math.max(1, window - overlap)) {
            int end = Math.min(content.length(), start + window);
            String text = content.substring(start, end).trim();
            double score = scopedScore(normalizedContent.substring(start, end), tokens, index);
            if (score > 0 || excerpts.isEmpty()) {
                excerpts.add(new ScopedExcerpt(index, text, score));
            }
            index++;
            if (end == content.length()) {
                break;
            }
        }
        return excerpts.stream()
            .sorted(Comparator
                .comparingDouble(ScopedExcerpt::score)
                .reversed()
                .thenComparingInt(ScopedExcerpt::index))
            .limit(maxChunks)
            .toList();
    }

    private double scopedScore(String normalizedText, List<String> tokens, int index) {
        double score = Math.max(0, 10 - index);
        for (String token : tokens) {
            if (hasText(token) && normalizedText.contains(token)) {
                score += token.length() > 2 ? 18 : 8;
            }
        }
        return Math.round(Math.min(100.0D, score) * 10.0D) / 10.0D;
    }

    private DocumentEvidenceChunk toEvidence(SearchResult result,
                                             SearchMatchedChunk chunk,
                                             String query,
                                             String intent,
                                             boolean debug) {
        String fileName = firstNonBlank(chunk.fileName(), result.fileName(), result.title());
        String section = nullToEmpty(chunk.section());
        String content = firstNonBlank(chunk.content(), chunk.text(), result.summary());
        Integer chunkIndex = chunk.chunkIndex();
        String fileId = firstNonBlank(chunk.fileId(), result.docId());
        String refId = refId(fileId, chunkIndex);
        return new DocumentEvidenceChunk(
            refId,
            firstNonBlank(chunk.chunkId(), result.docId() + "_" + chunkIndex),
            fileId,
            fileName,
            section,
            chunkIndex,
            nullToEmpty(chunk.chunkType()),
            normalizeScore(chunk.score(), result.score()),
            content,
            highlights(query, result.matchedKeywords(), content),
            citation(fileName, section, chunkIndex),
            debug ? trace(result, chunk, query, intent) : null,
            firstNonBlank(chunk.tenantId(), result.tenantId()),
            firstNonBlank(chunk.userId(), result.userId()),
            firstNonBlank(chunk.visibility(), result.visibility()),
            safeList(chunk.permissionRoles()).isEmpty() ? safeList(result.permissionRoles()) : safeList(chunk.permissionRoles())
        );
    }

    private void addSummaryEvidence(List<DocumentEvidenceChunk> chunks,
                                    SearchResult result,
                                    String query,
                                    String intent,
                                    boolean debug,
                                    DocumentSearchFilters filters) {
        if (filters != null && hasText(filters.chunkType())) {
            return;
        }
        String fileName = firstNonBlank(result.fileName(), result.title());
        String content = firstNonBlank(result.summary(), "");
        if (!hasText(content)) {
            return;
        }
        String refId = refId(result.docId(), null);
        chunks.add(new DocumentEvidenceChunk(
            refId,
            result.docId() + "_summary",
            result.docId(),
            fileName,
            "",
            null,
            "",
            normalizeScore(result.score(), result.score()),
            content,
            highlights(query, result.matchedKeywords(), content),
            citation(fileName, "", null),
            debug ? new SearchTrace(
                safeList(result.matchedKeywords()),
                intent,
                List.of(),
                "document summary matched"
            ) : null,
            result.tenantId(),
            result.userId(),
            result.visibility(),
            safeList(result.permissionRoles())
        ));
    }

    private SearchTrace trace(SearchResult result, SearchMatchedChunk chunk, String query, String intent) {
        List<String> matched = highlights(query, result.matchedKeywords(), firstNonBlank(chunk.content(), chunk.text()));
        List<String> reasons = new ArrayList<>();
        if (containsAny(chunk.section(), matched)) {
            reasons.add("section matched");
        }
        if (containsAny(chunk.chunkType(), matched)) {
            reasons.add("chunkType matched");
        }
        if (containsAny(firstNonBlank(chunk.content(), chunk.text()), matched)) {
            reasons.add("content matched");
        }
        if (chunk.score() > 0) {
            reasons.add("chunk score retained");
        }
        return new SearchTrace(
            matched,
            intent,
            List.of(),
            reasons.isEmpty() ? "retrieval evidence matched" : String.join(" + ", reasons)
        );
    }

    private Citation citation(String fileName, String section, Integer chunkIndex) {
        List<String> locators = new ArrayList<>();
        if (hasText(section)) {
            locators.add("section: " + section);
        }
        if (chunkIndex != null) {
            locators.add("chunk: " + chunkIndex);
        }
        return new Citation(firstNonBlank(fileName, "document"), String.join("; ", locators));
    }

    private String refId(String fileId, Integer chunkIndex) {
        return "doc://" + firstNonBlank(fileId, "unknown") + "#chunk=" + (chunkIndex == null ? "summary" : chunkIndex);
    }

    private List<String> highlights(String query, List<String> matchedKeywords, String content) {
        Set<String> values = new LinkedHashSet<>();
        for (String keyword : safeList(matchedKeywords)) {
            if (hasText(keyword) && appears(keyword, content)) {
                values.add(keyword.trim());
            }
        }
        for (String token : tokenizer.searchTokens(query)) {
            if (hasText(token) && appears(token, content)) {
                values.add(token.trim());
            }
        }
        return values.stream().limit(8).toList();
    }

    private boolean matchesFileType(SearchResult result, String fileType) {
        if (!hasText(fileType)) {
            return true;
        }
        String expected = fileType.trim().toLowerCase(Locale.ROOT);
        String documentType = nullToEmpty(result.documentType()).toLowerCase(Locale.ROOT);
        String fileName = nullToEmpty(result.fileName()).toLowerCase(Locale.ROOT);
        return expected.equals(documentType) || fileName.endsWith("." + expected);
    }

    private boolean matchesDocumentFilters(SearchDocument document, DocumentSearchFilters filters) {
        if (filters == null) {
            return true;
        }
        if (hasText(filters.fileType())) {
            String expected = filters.fileType().trim().toLowerCase(Locale.ROOT);
            String documentType = nullToEmpty(document.getDocumentType()).toLowerCase(Locale.ROOT);
            String fileName = nullToEmpty(document.getFileName()).toLowerCase(Locale.ROOT);
            if (!expected.equals(documentType) && !fileName.endsWith("." + expected)) {
                return false;
            }
        }
        if (hasText(filters.tag()) && !containsIgnoreCase(document.getTags(), filters.tag())) {
            return false;
        }
        if (hasText(filters.company()) && !containsIgnoreCase(document.getCompanies(), filters.company())) {
            return false;
        }
        return !hasText(filters.industry()) || containsIgnoreCase(document.getIndustries(), filters.industry());
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        String needle = expected == null ? "" : expected.trim().toLowerCase(Locale.ROOT);
        return safeList(values).stream()
            .filter(this::hasText)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.contains(needle) || needle.contains(value));
    }

    private boolean matchesChunkType(SearchMatchedChunk chunk, String chunkType) {
        if (!hasText(chunkType)) {
            return true;
        }
        return chunkType.trim().equalsIgnoreCase(nullToEmpty(chunk.chunkType()).trim());
    }

    private Double normalizeScore(float chunkScore, int resultScore) {
        double raw = chunkScore > 0 ? chunkScore * 10.0D : resultScore;
        return Math.round(Math.max(0.0D, Math.min(100.0D, raw)) * 10.0D) / 10.0D;
    }

    private int normalizeTopK(Integer value) {
        if (value == null) {
            return DEFAULT_TOP_K;
        }
        return Math.max(1, Math.min(MAX_TOP_K, value));
    }

    private String requireQuery(String query) {
        if (!hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        return query.trim();
    }

    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
            .filter(this::hasText)
            .map(String::trim)
            .reduce((left, right) -> left + "," + right)
            .orElse(null);
    }

    private boolean containsAny(String text, List<String> values) {
        if (!hasText(text) || values == null || values.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(this::hasText)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(normalized::contains);
    }

    private boolean appears(String keyword, String content) {
        if (!hasText(keyword)) {
            return false;
        }
        if (!hasText(content)) {
            return true;
        }
        return content.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record ScopedExcerpt(int index, String text, Double score) {
    }
}
