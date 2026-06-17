package com.chatchat.knowledgebase.search;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        int topK = normalizeTopK(request.topK());
        DocumentSearchFilters filters = request.filters();
        String fileIds = joinValues(request.fileIds());
        boolean debug = Boolean.TRUE.equals(request.debug());
        SearchPermissionContext permissionContext = SearchPermissionContext.of(request.tenantId(), request.userId(), request.roles());

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
}
