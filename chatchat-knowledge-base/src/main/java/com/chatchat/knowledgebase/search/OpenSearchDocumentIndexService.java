package com.chatchat.knowledgebase.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import javax.net.ssl.SSLContext;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenSearchDocumentIndexService implements DocumentSearchIndex {

    private static final String FILE_ID = "fileId";
    private static final String FILE_NAME = "fileName";
    private static final String CHUNK_ID = "chunkId";
    private static final String CHUNK_INDEX = "chunkIndex";
    private static final String CHUNK_TEXT = "chunkText";
    private static final String CONTENT = "content";
    private static final String DEFAULT_CONTENT_VECTOR = "contentVector";
    private static final String TITLE_TEXT = "title";
    private static final String SECTION = "section";
    private static final String KEYWORDS_TEXT = "keywords";
    private static final String CHUNK_TYPE = "chunkType";
    private static final String POSITION_RATIO = "positionRatio";
    private static final String SOURCE = "source";
    private static final String TAGS = "tags";
    private static final String COMPANIES = "companies";
    private static final String INDUSTRIES = "industries";
    private static final String TITLE_TOKENS = "titleTokens";
    private static final String CONTENT_TOKENS = "contentTokens";
    private static final String SOURCE_TOKENS = "sourceTokens";
    private static final String KEYWORD_TOKENS = "keywordTokens";
    private static final String TAG_TOKENS = "tagTokens";
    private static final String COMPANY_TOKENS = "companyTokens";
    private static final String INDUSTRY_TOKENS = "industryTokens";
    private static final String TENANT_ID = "tenantId";
    private static final String USER_ID = "userId";
    private static final String VISIBILITY = "visibility";
    private static final String PERMISSION_ROLE = "permissionRole";
    private static final String IK_INDEX_ANALYZER = "chatchat_ik_index";
    private static final String IK_SEARCH_ANALYZER = "chatchat_ik_search";
    private static final String PINYIN_ANALYZER = "chatchat_pinyin";
    private static final String WHITESPACE_ANALYZER = "chatchat_whitespace";
    private static final int SIMPLIFIED_QUERY_TERM_LIMIT = 10;
    private static final List<String> TOKEN_SEARCH_FIELDS = List.of(
        TITLE_TOKENS + "^5.0", KEYWORD_TOKENS + "^4.2", TAG_TOKENS + "^4.5",
        COMPANY_TOKENS + "^3.5", INDUSTRY_TOKENS + "^3.5", CONTENT_TOKENS + "^1.2"
    );
    private static final List<String> TEXT_SEARCH_FIELDS = List.of(
        TITLE_TEXT + "^5.0", FILE_NAME + "^4.0", SECTION + "^4.0",
        KEYWORDS_TEXT + "^4.0", CONTENT + "^1.2", CHUNK_TEXT + "^1.2"
    );
    private static final List<String> PINYIN_SEARCH_FIELDS = List.of(
        TITLE_TEXT + ".pinyin^3.2", FILE_NAME + ".pinyin^3.0",
        SECTION + ".pinyin^2.8", KEYWORDS_TEXT + ".pinyin^2.6"
    );
    private static final List<String> SIMPLIFIED_SEARCH_FIELDS = List.of(
        TITLE_TEXT + "^5.0", FILE_NAME + "^4.0", KEYWORDS_TEXT + "^3.0", CONTENT
    );

    private final SearchProperties properties;
    private final SearchTokenizer tokenizer;
    private final TextChunker chunker;
    private final KeywordExtractor keywordExtractor;
    private final QueryExpander queryExpander;
    private final ChunkTypeClassifier chunkTypeClassifier;
    private final OpenSearchEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private RestClient restClient;
    private volatile boolean available;
    private volatile boolean vectorAvailable;
    private volatile boolean vectorRerankAvailable;
    private volatile boolean knnSettingCheckWarningLogged;
    private volatile boolean knnDisabledLogged;

    @PostConstruct
    public void open() {
        SearchProperties.OpenSearch config = config();
        this.restClient = buildRestClient(config);
        if (!selected()) {
            return;
        }
        try {
            ensureIndex();
            available = true;
            log.info("OpenSearch document index ready url={} index={}", baseUrl(), indexName());
        } catch (Exception ex) {
            available = false;
            log.warn("OpenSearch document index is unavailable url={} index={} error={}",
                baseUrl(), indexName(), ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void close() {
        if (restClient == null) {
            return;
        }
        try {
            restClient.close();
        } catch (IOException ex) {
            log.warn("Failed to close OpenSearch REST client url={} error={}", baseUrl(), ex.getMessage());
        }
    }

    @Override
    public synchronized void indexLatest(SearchDocument document) {
        if (!isAvailable() || document == null || document.getDocId() == null || document.getDocId().isBlank()) {
            return;
        }
        long startedAt = System.nanoTime();
        deleteDocument(document.getDocId());
        if (Boolean.FALSE.equals(document.getLatestVersion())) {
            log.info("opensearch_index_document_deleted_non_latest docId={} index={} durationMs={}",
                document.getDocId(), indexName(), elapsedMs(startedAt));
            return;
        }
        List<Map<String, Object>> chunks = chunkDocuments(document);
        int vectorized = vectorizedCount(chunks);
        int vectorDimension = firstVectorDimension(chunks);
        long vectorBytes = estimatedVectorBytes(chunks);
        log.info(
            "opensearch_index_document_start docId={} index={} chunks={} vectorConfigured={} vectorSearchReady={} vectorRerankReady={} vectorized={} vectorDimension={} vectorBytes={}",
            document.getDocId(),
            indexName(),
            chunks.size(),
            embeddingClient.configured(),
            vectorAvailable,
            vectorRerankAvailable,
            vectorized,
            vectorDimension,
            vectorBytes
        );
        long bulkBytes = bulkIndex(chunks);
        log.info(
            "opensearch_index_document_complete docId={} index={} chunks={} vectorized={} vectorDimension={} vectorBytes={} bulkBytes={} durationMs={}",
            document.getDocId(),
            indexName(),
            chunks.size(),
            vectorized,
            vectorDimension,
            vectorBytes,
            bulkBytes,
            elapsedMs(startedAt)
        );
    }

    @Override
    public synchronized void deleteDocument(String docId) {
        if (!isAvailable() || docId == null || docId.isBlank()) {
            return;
        }
        Map<String, Object> body = Map.of("query", Map.of("term", Map.of(FILE_ID, docId)));
        request("POST", "/" + indexName() + "/_delete_by_query?conflicts=proceed&refresh=true", body, true);
    }

    @Override
    public synchronized void rebuildLatest(List<SearchDocument> documents) {
        if (!isAvailable()) {
            return;
        }
        request("POST", "/" + indexName() + "/_delete_by_query?conflicts=proceed&refresh=true",
            Map.of("query", Map.of("match_all", Map.of())), true);
        List<Map<String, Object>> batch = new ArrayList<>();
        for (SearchDocument document : documents == null ? List.<SearchDocument>of() : documents) {
            if (document == null || Boolean.FALSE.equals(document.getLatestVersion())) {
                continue;
            }
            batch.addAll(chunkDocuments(document));
            if (batch.size() >= Math.max(1, config().getBulkBatchSize())) {
                bulkIndex(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            bulkIndex(batch);
        }
    }

    @Override
    public List<LuceneSearchHit> search(String keyword, int maxHits) {
        return search(keyword, maxHits, SearchPermissionContext.system());
    }

    @Override
    public synchronized List<LuceneSearchHit> search(String keyword, int maxHits, SearchPermissionContext permissionContext) {
        if (!isAvailable() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String focusedKeyword = queryExpander.focusQuery(keyword);
        String normalizedKeyword = queryExpander.normalizeQuery(keyword);
        List<String> terms = queryExpander.expandTokens(
            tokenizer.searchTokens(normalizedKeyword),
            queryExpander.classifyIntentName(normalizedKeyword),
            normalizedKeyword
        ).stream()
            .filter(term -> term != null && !term.isBlank())
            .distinct()
            .limit(Math.max(1, properties.getLuceneMaxQueryTerms()))
            .toList();
        terms = limitTerms(
            mergeTerms(terms, cjkTitleAwareQueryTerms(normalizedKeyword)),
            Math.min(properties.getLuceneMaxQueryTerms(), Math.max(1, config().getMaxQueryTerms()))
        );
        if (terms.isEmpty()) {
            return List.of();
        }
        int candidateLimit = Math.max(Math.max(1, maxHits), embeddingConfig().getVectorCandidateLimit());
        Map<String, List<Float>> lexicalVectors = new LinkedHashMap<>();
        List<LuceneSearchHit> lexicalHits = lexicalSearch(focusedKeyword, terms, candidateLimit, permissionContext, lexicalVectors);
        List<LuceneSearchHit> vectorHits = vectorSearch(normalizedKeyword, candidateLimit, permissionContext);
        if (vectorHits.isEmpty()) {
            vectorHits = vectorRerankHits(normalizedKeyword, lexicalHits, lexicalVectors, candidateLimit);
        }
        if (vectorHits.isEmpty()) {
            return lexicalHits.stream().limit(Math.max(1, maxHits)).toList();
        }
        return hybridMerge(lexicalHits, vectorHits, normalizedKeyword, Math.max(1, maxHits));
    }

    private List<LuceneSearchHit> lexicalSearch(
        String normalizedKeyword,
        List<String> terms,
        int maxHits,
        SearchPermissionContext permissionContext,
        Map<String, List<Float>> sourceVectors
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Math.max(1, maxHits));
        body.put("query", searchQuery(normalizedKeyword, terms, permissionContext));
        logSearchQuery("primary", normalizedKeyword, terms, body);
        JsonNode root;
        try {
            root = request("POST", "/" + indexName() + "/_search", body, false);
        } catch (IllegalStateException ex) {
            if (!isClauseOverflow(ex)) {
                throw ex;
            }
            Map<String, Object> simplifiedBody = new LinkedHashMap<>();
            simplifiedBody.put("size", Math.max(1, maxHits));
            simplifiedBody.put("query", simplifiedSearchQuery(terms, permissionContext));
            log.warn("opensearch_query_clause_overflow index={} terms={} action=simplify_once",
                indexName(), terms == null ? 0 : terms.size());
            logSearchQuery("simplified", normalizedKeyword, terms, simplifiedBody);
            root = request("POST", "/" + indexName() + "/_search", simplifiedBody, false);
        }
        return parseHits(root, sourceVectors);
    }

    private List<LuceneSearchHit> vectorSearch(
        String normalizedKeyword,
        int maxHits,
        SearchPermissionContext permissionContext
    ) {
        if (!vectorSearchAvailable()) {
            return List.of();
        }
        List<Float> vector = embeddingClient.embed(normalizedKeyword);
        if (vector.isEmpty()) {
            return List.of();
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(Map.of(
            "knn", Map.of(vectorField(), Map.of(
                "vector", vector,
                "k", Math.max(1, maxHits)
            ))
        )));
        bool.put("filter", searchFilters(permissionContext));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Math.max(1, maxHits));
        body.put("query", Map.of("bool", bool));
        try {
            JsonNode root = request("POST", "/" + indexName() + "/_search", body, false);
            return parseHits(root, null);
        } catch (Exception ex) {
            log.warn("OpenSearch vector search skipped index={} field={} error={}",
                indexName(), vectorField(), ex.getMessage());
            return List.of();
        }
    }

    private List<LuceneSearchHit> vectorRerankHits(
        String normalizedKeyword,
        List<LuceneSearchHit> lexicalHits,
        Map<String, List<Float>> sourceVectors,
        int maxHits
    ) {
        if (!vectorRerankAvailable || !embeddingClient.enabled() || lexicalHits.isEmpty() || sourceVectors.isEmpty()) {
            return List.of();
        }
        List<Float> queryVector = embeddingClient.embed(normalizedKeyword);
        if (queryVector.isEmpty()) {
            return List.of();
        }
        List<LuceneSearchHit> hits = new ArrayList<>();
        for (LuceneSearchHit hit : lexicalHits) {
            List<Float> vector = sourceVectors.get(hitKey(hit));
            float score = cosine(queryVector, vector);
            if (score > 0.0F) {
                hits.add(withScore(hit, score));
            }
        }
        return hits.stream()
            .sorted(Comparator.comparing(LuceneSearchHit::score).reversed())
            .limit(Math.max(1, maxHits))
            .toList();
    }

    private List<LuceneSearchHit> parseHits(JsonNode root, Map<String, List<Float>> sourceVectors) {
        List<LuceneSearchHit> hits = new ArrayList<>();
        JsonNode hitNodes = root.path("hits").path("hits");
        if (!hitNodes.isArray()) {
            return List.of();
        }
        for (JsonNode hitNode : hitNodes) {
            JsonNode source = hitNode.path("_source");
            String docId = text(source, FILE_ID);
            if (docId == null || docId.isBlank()) {
                continue;
            }
            LuceneSearchHit hit = new LuceneSearchHit(
                docId,
                text(source, FILE_NAME),
                text(source, SECTION),
                text(source, CHUNK_TYPE),
                text(source, CHUNK_ID),
                intValue(source.path(CHUNK_INDEX), 0),
                firstText(text(source, CONTENT), text(source, CHUNK_TEXT)),
                floatValue(source.path(POSITION_RATIO), 1.0F),
                floatValue(hitNode.path("_score"), 0.0F),
                text(source, TENANT_ID),
                text(source, USER_ID),
                text(source, VISIBILITY),
                stringList(source.path(PERMISSION_ROLE))
            );
            hits.add(hit);
            if (sourceVectors != null) {
                List<Float> vector = floatList(source.path(vectorField()));
                if (!vector.isEmpty()) {
                    sourceVectors.put(hitKey(hit), vector);
                }
            }
        }
        return hits;
    }

    private List<LuceneSearchHit> hybridMerge(
        List<LuceneSearchHit> lexicalHits,
        List<LuceneSearchHit> vectorHits,
        String keyword,
        int maxHits
    ) {
        SearchProperties.OpenSearch.Embedding config = embeddingConfig();
        float maxLexical = maxScore(lexicalHits);
        float maxVector = maxScore(vectorHits);
        Map<String, HybridHit> merged = new LinkedHashMap<>();
        for (LuceneSearchHit hit : lexicalHits) {
            HybridHit score = merged.computeIfAbsent(hitKey(hit), key -> new HybridHit(hit));
            score.lexical = normalizedScore(hit.score(), maxLexical);
        }
        for (LuceneSearchHit hit : vectorHits) {
            HybridHit score = merged.computeIfAbsent(hitKey(hit), key -> new HybridHit(hit));
            score.vector = normalizedScore(hit.score(), maxVector);
        }
        String lowerKeyword = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return merged.values().stream()
            .map(hit -> hit.withScore(hybridScore(hit, lowerKeyword, config)))
            .sorted(Comparator.comparing(LuceneSearchHit::score).reversed())
            .limit(Math.max(1, maxHits))
            .toList();
    }

    @Override
    public boolean isAvailable() {
        return selected() && available;
    }

    public boolean isVectorSearchReady() {
        return vectorSearchAvailable();
    }

    public boolean isVectorRerankReady() {
        return vectorIndexingAvailable();
    }

    public boolean isEmbeddingConfigured() {
        return embeddingClient.configured();
    }

    public boolean isEmbeddingEnabled() {
        return embeddingClient.enabled();
    }

    private boolean selected() {
        SearchProperties.OpenSearch config = config();
        return properties.isOpenSearchEngine() && config.isEnabled();
    }

    private void ensureIndex() {
        if (request("HEAD", "/" + indexName(), null, true) != null) {
            ensureVectorMappingIfNecessary();
            return;
        }
        boolean vectorConfigured = embeddingClient.configured();
        if (vectorConfigured) {
            try {
                request("PUT", "/" + indexName(), indexCreateBody(true), false);
                vectorAvailable = true;
                vectorRerankAvailable = true;
                return;
            } catch (Exception ex) {
                vectorAvailable = false;
                vectorRerankAvailable = false;
                log.warn("OpenSearch KNN index creation failed index={} field={} error={}. "
                        + "Creating lexical index and falling back when vector search is unavailable.",
                    indexName(), vectorField(), ex.getMessage());
            }
        }
        request("PUT", "/" + indexName(), indexCreateBody(false), false);
        ensureVectorMappingIfNecessary();
    }

    private Map<String, Object> indexCreateBody(boolean includeVector) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (includeVector) {
            settings.put("index", Map.of("knn", true));
        }
        settings.put("analysis", analysisSettings());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FILE_ID, Map.of("type", "keyword"));
        fields.put(CHUNK_ID, Map.of("type", "keyword"));
        fields.put(CHUNK_INDEX, Map.of("type", "integer"));
        fields.put(FILE_NAME, chineseTextMapping(true));
        fields.put(TITLE_TEXT, chineseTextMapping(true));
        fields.put(SECTION, chineseTextMapping(true));
        fields.put(KEYWORDS_TEXT, chineseTextMapping(true));
        fields.put(CONTENT, chineseTextMapping(false));
        fields.put(CHUNK_TEXT, chineseTextMapping(false));
        fields.put(SOURCE, chineseTextMapping(true));
        fields.put(TAGS, Map.of("type", "keyword"));
        fields.put(COMPANIES, Map.of("type", "keyword"));
        fields.put(INDUSTRIES, Map.of("type", "keyword"));
        fields.put(TITLE_TOKENS, tokenFieldMapping());
        fields.put(CONTENT_TOKENS, tokenFieldMapping());
        fields.put(SOURCE_TOKENS, tokenFieldMapping());
        fields.put(KEYWORD_TOKENS, tokenFieldMapping());
        fields.put(TAG_TOKENS, tokenFieldMapping());
        fields.put(COMPANY_TOKENS, tokenFieldMapping());
        fields.put(INDUSTRY_TOKENS, tokenFieldMapping());
        fields.put(TENANT_ID, Map.of("type", "keyword"));
        fields.put(USER_ID, Map.of("type", "keyword"));
        fields.put(VISIBILITY, Map.of("type", "keyword"));
        fields.put(PERMISSION_ROLE, Map.of("type", "keyword"));
        fields.put(CHUNK_TYPE, Map.of("type", "keyword"));
        fields.put(POSITION_RATIO, Map.of("type", "float"));
        if (includeVector) {
            fields.put(vectorField(), vectorFieldMapping());
        }
        Map<String, Object> body = Map.of(
            "settings", settings,
            "mappings", Map.of("properties", fields)
        );
        return body;
    }

    Map<String, Object> searchQuery(String keyword, List<String> terms, SearchPermissionContext permissionContext) {
        List<String> boundedTerms = limitTerms(
            terms,
            Math.min(Math.max(1, config().getMaxQueryTerms()), Math.max(1, properties.getLuceneMaxQueryTerms()))
        );
        List<Object> should = new ArrayList<>();
        if (!boundedTerms.isEmpty()) {
            should.add(multiMatchQuery(
                String.join(" ", boundedTerms),
                TOKEN_SEARCH_FIELDS
            ));
            should.add(Map.of("terms", Map.of(TAGS, boundedTerms)));
            should.add(Map.of("terms", Map.of(COMPANIES, boundedTerms)));
            should.add(Map.of("terms", Map.of(INDUSTRIES, boundedTerms)));
        }
        String boundedKeyword = limitQueryText(keyword, config().getMaxQueryChars());
        if (boundedKeyword != null) {
            should.add(multiMatchQuery(
                boundedKeyword,
                TEXT_SEARCH_FIELDS
            ));
            should.add(multiMatchQuery(
                pinyinQueryText(boundedKeyword, boundedTerms),
                PINYIN_SEARCH_FIELDS
            ));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", should);
        bool.put("minimum_should_match", 1);
        bool.put("filter", searchFilters(permissionContext));
        return Map.of("bool", bool);
    }

    private Map<String, Object> simplifiedSearchQuery(List<String> terms, SearchPermissionContext permissionContext) {
        List<String> boundedTerms = limitTerms(terms, SIMPLIFIED_QUERY_TERM_LIMIT);
        String query = boundedTerms.isEmpty() ? "_none_" : String.join(" ", boundedTerms);
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(multiMatchQuery(query, SIMPLIFIED_SEARCH_FIELDS)));
        bool.put("filter", searchFilters(permissionContext));
        return Map.of("bool", bool);
    }

    private Map<String, Object> multiMatchQuery(String query, List<String> fields) {
        return Map.of("multi_match", Map.of(
            "query", query,
            "fields", fields,
            "operator", "or"
        ));
    }

    private String limitQueryText(String query, int maxChars) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim();
        int limit = Math.max(1, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String pinyinQueryText(String boundedKeyword, List<String> boundedTerms) {
        List<String> pinyinTerms = limitTerms(boundedTerms, SIMPLIFIED_QUERY_TERM_LIMIT);
        return pinyinTerms.isEmpty() ? boundedKeyword : String.join(" ", pinyinTerms);
    }

    private void logSearchQuery(String mode, String keyword, List<String> terms, Map<String, Object> body) {
        int termCount = terms == null ? 0 : terms.size();
        log.info(
            "opensearch_search_query_stats index={} mode={} queryChars={} terms={} tokenFields={} textFields={} pinyinFields={} permissionRoleLimit={}",
            indexName(), mode, keyword == null ? 0 : keyword.length(), termCount,
            TOKEN_SEARCH_FIELDS.size(), TEXT_SEARCH_FIELDS.size(), PINYIN_SEARCH_FIELDS.size(),
            Math.max(0, config().getMaxPermissionRoles())
        );
        if (config().isLogQueryDsl()) {
            log.info("opensearch_search_query_dsl index={} mode={} body={}", indexName(), mode, json(body));
        } else if (log.isDebugEnabled()) {
            log.debug("opensearch_search_query_dsl index={} mode={} body={}", indexName(), mode, json(body));
        }
    }

    boolean isClauseOverflow(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("too_many_nested_clauses")
                || message.contains("too_many_clauses")
                || message.contains("maxclausecount")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<Object> searchFilters(SearchPermissionContext permissionContext) {
        List<Object> filters = new ArrayList<>();
        if (properties.isTenantIsolationEnabled()) {
            filters.add(Map.of("term", Map.of(TENANT_ID, normalizeTenant(permissionContext == null ? null : permissionContext.tenantId()))));
        }
        filters.add(permissionFilter(permissionContext));
        return filters;
    }

    private Map<String, Object> permissionFilter(SearchPermissionContext permissionContext) {
        SearchPermissionContext context = permissionContext == null ? SearchPermissionContext.system() : permissionContext;
        String userId = normalizeUser(context.userId());
        List<Object> should = new ArrayList<>();
        should.add(Map.of("term", Map.of(VISIBILITY, "tenant")));
        should.add(Map.of("term", Map.of(VISIBILITY, "public")));
        should.add(Map.of("bool", Map.of("must", List.of(
            Map.of("term", Map.of(VISIBILITY, "private")),
            Map.of("term", Map.of(USER_ID, userId))
        ))));
        for (String role : normalizeRoles(context.roles()).stream()
            .limit(Math.max(0, config().getMaxPermissionRoles()))
            .toList()) {
            should.add(Map.of("bool", Map.of("must", List.of(
                Map.of("term", Map.of(VISIBILITY, "role")),
                Map.of("term", Map.of(PERMISSION_ROLE, role))
            ))));
        }
        should.add(Map.of("bool", Map.of("must", List.of(
            Map.of("term", Map.of(VISIBILITY, "role")),
            Map.of("term", Map.of(USER_ID, userId))
        ))));
        return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
    }

    private List<Map<String, Object>> chunkDocuments(SearchDocument document) {
        List<TextChunker.TextChunk> chunks = chunker.splitChunks(
            document.getContent(),
            properties.getChunkSize(),
            properties.getChunkOverlap()
        );
        if (chunks.isEmpty()) {
            chunks = List.of(new TextChunker.TextChunk("", ""));
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.TextChunk chunk = chunks.get(i);
            String chunkText = nullToEmpty(chunk.content());
            Map<String, Object> source = new LinkedHashMap<>();
            source.put(FILE_ID, document.getDocId());
            source.put(FILE_NAME, nullToEmpty(document.getFileName()));
            source.put(CHUNK_ID, document.getDocId() + "_" + i);
            source.put(CHUNK_INDEX, i);
            source.put(CHUNK_TEXT, chunkText);
            source.put(CONTENT, chunkText);
            source.put(TITLE_TEXT, nullToEmpty(document.getTitle()));
            source.put(SECTION, nullToEmpty(chunk.section()));
            source.put(KEYWORDS_TEXT, String.join(" ", keywordExtractor.mergeKeywords(document.getKeywords(), chunkText)));
            source.put(CHUNK_TYPE, chunkTypeClassifier.classify(document, chunk));
            source.put(POSITION_RATIO, positionRatio(i, chunks.size()));
            source.put(SOURCE, nullToEmpty(document.getSource()));
            source.put(TAGS, cleanList(document.getTags()));
            source.put(COMPANIES, cleanList(document.getCompanies()));
            source.put(INDUSTRIES, cleanList(document.getIndustries()));
            source.put(TITLE_TOKENS, String.join(" ", TitleAwareTerms.extract(tokenizer, document.getTitle(), document.getFileName())));
            source.put(CONTENT_TOKENS, tokenText(chunkText));
            source.put(SOURCE_TOKENS, tokenText(document.getSource()));
            source.put(KEYWORD_TOKENS, tokenText(document.getKeywords()));
            source.put(TAG_TOKENS, tokenText(document.getTags()));
            source.put(COMPANY_TOKENS, tokenText(document.getCompanies()));
            source.put(INDUSTRY_TOKENS, tokenText(document.getIndustries()));
            source.put(TENANT_ID, normalizeTenant(document.getTenantId()));
            source.put(USER_ID, normalizeUser(document.getUserId()));
            source.put(VISIBILITY, normalizeVisibility(document.getVisibility()));
            source.put(PERMISSION_ROLE, normalizeRoles(document.getPermissionRoles()));
            List<Float> vector = vectorIndexingAvailable()
                ? embeddingClient.embed(embeddingInput(document, chunkText, chunk.section()))
                : List.of();
            if (!vector.isEmpty()) {
                source.put(vectorField(), vector);
            }
            docs.add(source);
        }
        return docs;
    }

    private long bulkIndex(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0L;
        }
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> doc : docs) {
            String chunkId = String.valueOf(doc.get(CHUNK_ID));
            body.append(json(Map.of("index", Map.of("_index", indexName(), "_id", chunkId)))).append('\n');
            body.append(json(doc)).append('\n');
        }
        String payload = body.toString();
        requestRaw("POST", "/_bulk?refresh=true", payload, false, "application/x-ndjson");
        return payload.getBytes(StandardCharsets.UTF_8).length;
    }

    private int vectorizedCount(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> doc : docs) {
            if (doc != null && doc.get(vectorField()) instanceof List<?> vector && !vector.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int firstVectorDimension(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> doc : docs) {
            if (doc != null && doc.get(vectorField()) instanceof List<?> vector && !vector.isEmpty()) {
                return vector.size();
            }
        }
        return 0;
    }

    private long estimatedVectorBytes(List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Map<String, Object> doc : docs) {
            if (doc != null && doc.get(vectorField()) instanceof List<?> vector && !vector.isEmpty()) {
                total += (long) vector.size() * Float.BYTES;
            }
        }
        return total;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private Map<String, Object> tokenFieldMapping() {
        return Map.of("type", "text", "analyzer", WHITESPACE_ANALYZER, "search_analyzer", WHITESPACE_ANALYZER);
    }

    private Map<String, Object> analysisSettings() {
        return Map.of(
            "filter", Map.of(
                "chatchat_pinyin_filter", Map.of(
                    "type", "pinyin",
                    "keep_full_pinyin", true,
                    "keep_joined_full_pinyin", true,
                    "keep_original", true,
                    "limit_first_letter_length", 16,
                    "lowercase", true,
                    "remove_duplicated_term", true
                )
            ),
            "analyzer", Map.of(
                WHITESPACE_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "whitespace",
                    "filter", List.of("lowercase")
                ),
                IK_INDEX_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "ik_max_word",
                    "filter", List.of("lowercase", "icu_normalizer")
                ),
                IK_SEARCH_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "ik_smart",
                    "filter", List.of("lowercase", "icu_normalizer")
                ),
                PINYIN_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "keyword",
                    "filter", List.of("chatchat_pinyin_filter")
                )
            )
        );
    }

    private Map<String, Object> chineseTextMapping(boolean pinyinSubField) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("type", "text");
        mapping.put("analyzer", IK_INDEX_ANALYZER);
        mapping.put("search_analyzer", IK_SEARCH_ANALYZER);
        if (pinyinSubField) {
            mapping.put("fields", Map.of(
                "keyword", Map.of("type", "keyword"),
                "pinyin", Map.of("type", "text", "analyzer", PINYIN_ANALYZER, "search_analyzer", PINYIN_ANALYZER)
            ));
        }
        return mapping;
    }

    private Map<String, Object> vectorFieldMapping() {
        return Map.of(
            "type", "knn_vector",
            "dimension", Math.max(1, embeddingConfig().getDimension())
        );
    }

    private void ensureVectorMappingIfNecessary() {
        vectorAvailable = false;
        vectorRerankAvailable = false;
        if (!embeddingClient.configured()) {
            return;
        }
        try {
            JsonNode mapping = request("GET", "/" + indexName() + "/_mapping", null, false);
            if (mappingHasVectorField(mapping)) {
                boolean knnEnabled = indexKnnEnabled();
                vectorAvailable = knnEnabled;
                vectorRerankAvailable = true;
                if (!knnEnabled) {
                    logKnnDisabledOnce("OpenSearch KNN vector field exists but index.knn is disabled index={} field={}. "
                            + "ANN search is unavailable; using vector rerank over lexical candidates.");
                }
                return;
            }
            if (mappingHasFloatVectorField(mapping)) {
                vectorRerankAvailable = true;
                return;
            }
            boolean knnEnabled = ensureKnnIndexSettingIfNecessary();
            if (knnEnabled && tryAddKnnVectorMapping()) {
                vectorAvailable = true;
                vectorRerankAvailable = true;
                log.info("OpenSearch KNN vector field mapping added index={} field={} dimension={}",
                    indexName(), vectorField(), embeddingConfig().getDimension());
                return;
            }
            if (tryAddFloatVectorMapping()) {
                vectorRerankAvailable = true;
                log.info("OpenSearch float vector field mapping added index={} field={}. "
                        + "KNN is unavailable, using vector rerank over lexical candidates.",
                    indexName(), vectorField());
            }
        } catch (Exception ex) {
            vectorAvailable = false;
            vectorRerankAvailable = false;
            log.warn("OpenSearch vector mapping is not ready index={} field={} error={}. "
                    + "Use a new index name or rebuild the index if vector search is enabled.",
                indexName(), vectorField(), ex.getMessage());
        }
    }

    private boolean tryAddKnnVectorMapping() {
        try {
            request("PUT", "/" + indexName() + "/_mapping",
                Map.of("properties", Map.of(vectorField(), vectorFieldMapping())), false);
            return true;
        } catch (Exception ex) {
            log.warn("OpenSearch KNN vector mapping unsupported index={} field={} error={}",
                indexName(), vectorField(), ex.getMessage());
            return false;
        }
    }

    private boolean tryAddFloatVectorMapping() {
        try {
            request("PUT", "/" + indexName() + "/_mapping",
                Map.of("properties", Map.of(vectorField(), Map.of("type", "float"))), false);
            return true;
        } catch (Exception ex) {
            log.warn("OpenSearch float vector mapping failed index={} field={} error={}",
                indexName(), vectorField(), ex.getMessage());
            return false;
        }
    }

    private boolean ensureKnnIndexSettingIfNecessary() {
        if (indexKnnEnabled()) {
            return true;
        }
        logKnnDisabledOnce("OpenSearch index.knn is disabled on existing index={} field={}. "
            + "OpenSearch cannot update this final setting in-place; using lexical search with optional vector rerank.");
        return false;
    }

    private boolean indexKnnEnabled() {
        try {
            JsonNode settings = request("GET", "/" + indexName() + "/_settings", null, false);
            JsonNode indexNode = settings.path(indexName());
            if (indexNode.isMissingNode() && settings.fields().hasNext()) {
                indexNode = settings.fields().next().getValue();
            }
            return "true".equalsIgnoreCase(indexNode.path("settings").path("index").path("knn").asText());
        } catch (Exception ex) {
            if (!knnSettingCheckWarningLogged) {
                knnSettingCheckWarningLogged = true;
                log.warn("OpenSearch KNN index setting check failed index={} error={}. "
                        + "Skipping ANN search for this startup; lexical/vector-rerank can still be used if mapping is available.",
                    indexName(), ex.getMessage());
            }
            return false;
        }
    }

    private void logKnnDisabledOnce(String message) {
        if (knnDisabledLogged) {
            return;
        }
        knnDisabledLogged = true;
        log.info(message, indexName(), vectorField());
    }

    private JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        String raw = body == null ? "" : json(body);
        return requestRaw(method, path, raw, allowNotFound, "application/json");
    }

    private JsonNode requestRaw(String method, String path, String body, boolean allowNotFound, String contentType) {
        try {
            Request request = new Request(method.toUpperCase(Locale.ROOT), endpointPath(path));
            applyQueryParameters(request, path);
            if (!body.isBlank() || !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                request.setEntity(new StringEntity(body, ContentType.create(contentType, StandardCharsets.UTF_8)));
            }
            Response response = restClient.performRequest(request);
            StatusLine status = response.getStatusLine();
            String responseBody = entityString(response.getEntity());
            if (allowNotFound && status.getStatusCode() == 404) {
                return null;
            }
            if (status.getStatusCode() < 200 || status.getStatusCode() >= 300) {
                throw new IllegalStateException("OpenSearch request failed status=" + status.getStatusCode()
                    + " path=" + path + " body=" + responseBody);
            }
            if (responseBody == null || responseBody.isBlank() || "HEAD".equalsIgnoreCase(method)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (ResponseException ex) {
            Response response = ex.getResponse();
            int status = response == null ? 0 : response.getStatusLine().getStatusCode();
            if (allowNotFound && status == 404) {
                return null;
            }
            String responseBody = response == null ? "" : entityStringQuietly(response.getEntity());
            throw new IllegalStateException("OpenSearch request failed status=" + status
                + " path=" + path + " body=" + responseBody, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("OpenSearch request failed path=" + path + ": " + ex.getMessage(), ex);
        }
    }

    private RestClient buildRestClient(SearchProperties.OpenSearch config) {
        URI uri = URI.create(baseUrl());
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), port(uri), scheme(uri)))
            .setRequestConfigCallback(requestConfig -> requestConfig
                .setConnectTimeout(Math.max(1, config.getConnectTimeoutMs()))
                .setSocketTimeout(Math.max(1, config.getRequestTimeoutMs())));
        String pathPrefix = pathPrefix(uri);
        if (!pathPrefix.isBlank()) {
            builder.setPathPrefix(pathPrefix);
        }

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        if (!blank(config.getUsername()) || !blank(config.getPassword())) {
            credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(nullToEmpty(config.getUsername()), nullToEmpty(config.getPassword())));
        }
        builder.setHttpClientConfigCallback(httpClient -> {
            httpClient.setDefaultCredentialsProvider(credentialsProvider);
            if (config.isInsecureSsl()) {
                httpClient.setSSLContext(insecureSslContext());
                httpClient.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }
            return httpClient;
        });
        return builder.build();
    }

    private SSLContext insecureSslContext() {
        try {
            return SSLContexts.custom()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to create insecure OpenSearch SSL context", ex);
        }
    }

    private String entityString(HttpEntity entity) throws IOException {
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private String entityStringQuietly(HttpEntity entity) {
        try {
            return entityString(entity);
        } catch (IOException ex) {
            return "Failed to read OpenSearch response body: " + ex.getMessage();
        }
    }

    private String endpointPath(String path) {
        int queryIndex = path.indexOf('?');
        return queryIndex < 0 ? path : path.substring(0, queryIndex);
    }

    private void applyQueryParameters(Request request, String path) {
        int queryIndex = path.indexOf('?');
        if (queryIndex < 0 || queryIndex == path.length() - 1) {
            return;
        }
        String query = path.substring(queryIndex + 1);
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int equalsIndex = pair.indexOf('=');
            String key = equalsIndex < 0 ? pair : pair.substring(0, equalsIndex);
            String value = equalsIndex < 0 ? "" : pair.substring(equalsIndex + 1);
            request.addParameter(urlDecode(key), urlDecode(value));
        }
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int port(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(scheme(uri)) ? 443 : 80;
    }

    private String scheme(URI uri) {
        return uri.getScheme() == null || uri.getScheme().isBlank() ? "http" : uri.getScheme();
    }

    private String pathPrefix(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OpenSearch payload", ex);
        }
    }

    private SearchProperties.OpenSearch config() {
        return properties.getOpenSearch() == null ? new SearchProperties.OpenSearch() : properties.getOpenSearch();
    }

    private String baseUrl() {
        return trimTrailingSlash(config().getUrl());
    }

    private String indexName() {
        String value = config().getIndexName();
        return value == null || value.isBlank() ? "chatchat_documents" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimTrailingSlash(String value) {
        String text = value == null || value.isBlank() ? "http://localhost:9200" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private float positionRatio(int index, int count) {
        if (count <= 1) {
            return 0.0F;
        }
        return (float) index / (float) (count - 1);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private int intValue(JsonNode value, int fallback) {
        return value == null || !value.isNumber() ? fallback : value.asInt();
    }

    private float floatValue(JsonNode value, float fallback) {
        return value == null || !value.isNumber() ? fallback : (float) value.asDouble();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (!item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private List<Float> floatList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Float> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isNumber()) {
                values.add((float) item.asDouble());
            }
        }
        return values;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String tokenText(String value) {
        return String.join(" ", tokenizer.tokenizeOccurrences(value));
    }

    private String tokenText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> tokens = new ArrayList<>();
        for (String value : values) {
            tokens.addAll(tokenizer.tokenizeOccurrences(value));
        }
        return String.join(" ", tokens);
    }

    private List<String> cjkTitleAwareQueryTerms(String keyword) {
        if (!TitleAwareTerms.containsCjk(keyword)) {
            return List.of();
        }
        return TitleAwareTerms.extract(tokenizer, keyword);
    }

    private List<String> mergeTerms(List<String> baseTerms, List<String> additionalTerms) {
        Set<String> terms = new LinkedHashSet<>(baseTerms == null ? List.of() : baseTerms);
        if (additionalTerms != null) {
            additionalTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .forEach(terms::add);
        }
        return new ArrayList<>(terms);
    }

    private List<String> limitTerms(List<String> terms, int maxTerms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        int limit = maxTerms <= 0 ? terms.size() : maxTerms;
        return terms.stream()
            .filter(term -> term != null && !term.isBlank())
            .map(String::trim)
            .distinct()
            .limit(limit)
            .toList();
    }

    private List<String> normalizeRoles(List<String> roles) {
        return cleanList(roles).stream()
            .map(role -> role.toLowerCase(Locale.ROOT))
            .toList();
    }

    private boolean mappingHasVectorField(JsonNode mapping) {
        JsonNode propertiesNode = mapping.path(indexName()).path("mappings").path("properties");
        if (propertiesNode.isMissingNode() && mapping.fields().hasNext()) {
            propertiesNode = mapping.fields().next().getValue().path("mappings").path("properties");
        }
        JsonNode field = propertiesNode.path(vectorField());
        return !field.isMissingNode()
            && "knn_vector".equalsIgnoreCase(field.path("type").asText())
            && field.path("dimension").asInt(embeddingConfig().getDimension()) == embeddingConfig().getDimension();
    }

    private boolean mappingHasFloatVectorField(JsonNode mapping) {
        JsonNode propertiesNode = mapping.path(indexName()).path("mappings").path("properties");
        if (propertiesNode.isMissingNode() && mapping.fields().hasNext()) {
            propertiesNode = mapping.fields().next().getValue().path("mappings").path("properties");
        }
        JsonNode field = propertiesNode.path(vectorField());
        return !field.isMissingNode() && "float".equalsIgnoreCase(field.path("type").asText());
    }

    private String vectorField() {
        String configured = embeddingConfig().getVectorField();
        return configured == null || configured.isBlank() ? DEFAULT_CONTENT_VECTOR : configured.trim();
    }

    private SearchProperties.OpenSearch.Embedding embeddingConfig() {
        SearchProperties.OpenSearch openSearch = config();
        return openSearch.getEmbedding() == null ? new SearchProperties.OpenSearch.Embedding() : openSearch.getEmbedding();
    }

    private boolean vectorSearchAvailable() {
        return vectorAvailable && embeddingClient.enabled();
    }

    private boolean vectorIndexingAvailable() {
        return (vectorAvailable || vectorRerankAvailable) && embeddingClient.enabled();
    }

    private String embeddingInput(SearchDocument document, String chunkText, String section) {
        List<String> parts = new ArrayList<>();
        addPart(parts, document.getTitle());
        addPart(parts, document.getFileName());
        addPart(parts, section);
        addPart(parts, chunkText);
        return String.join("\n", parts);
    }

    private void addPart(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private float maxScore(List<LuceneSearchHit> hits) {
        float max = 0.0F;
        for (LuceneSearchHit hit : hits == null ? List.<LuceneSearchHit>of() : hits) {
            max = Math.max(max, hit.score());
        }
        return max;
    }

    private float normalizedScore(float score, float maxScore) {
        if (maxScore <= 0.0F || score <= 0.0F) {
            return 0.0F;
        }
        return Math.min(1.0F, score / maxScore);
    }

    private float cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0F;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm <= 0.0D || rightNorm <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.max(0.0D, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)));
    }

    private LuceneSearchHit withScore(LuceneSearchHit hit, float score) {
        return new LuceneSearchHit(
            hit.docId(),
            hit.fileName(),
            hit.section(),
            hit.chunkType(),
            hit.chunkId(),
            hit.chunkIndex(),
            hit.chunkText(),
            hit.positionRatio(),
            score,
            hit.tenantId(),
            hit.userId(),
            hit.visibility(),
            hit.permissionRoles()
        );
    }

    private float hybridScore(HybridHit hit, String lowerKeyword, SearchProperties.OpenSearch.Embedding config) {
        return hit.lexical * config.getBm25Weight()
            + hit.vector * config.getVectorWeight()
            + titleSignal(hit.hit, lowerKeyword) * config.getTitleWeight()
            + freshnessSignal(hit.hit) * config.getFreshnessWeight()
            + authoritySignal(hit.hit) * config.getAuthorityWeight();
    }

    private float titleSignal(LuceneSearchHit hit, String lowerKeyword) {
        if (lowerKeyword == null || lowerKeyword.isBlank()) {
            return 0.0F;
        }
        String titleLike = (nullToEmpty(hit.fileName()) + " " + nullToEmpty(hit.section())).toLowerCase(Locale.ROOT);
        return titleLike.contains(lowerKeyword) ? 1.0F : 0.0F;
    }

    private float freshnessSignal(LuceneSearchHit hit) {
        return Math.max(0.0F, 1.0F - hit.positionRatio());
    }

    private float authoritySignal(LuceneSearchHit hit) {
        return switch (normalizeVisibility(hit.visibility())) {
            case "public", "tenant" -> 1.0F;
            case "role" -> 0.8F;
            default -> 0.6F;
        };
    }

    private String hitKey(LuceneSearchHit hit) {
        if (hit.chunkId() != null && !hit.chunkId().isBlank()) {
            return hit.chunkId();
        }
        return hit.docId() + "_" + hit.chunkIndex();
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }

    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null ? "" : visibility.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "private", "role", "public" -> normalized;
            default -> "tenant";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class HybridHit {
        private final LuceneSearchHit hit;
        private float lexical;
        private float vector;

        private HybridHit(LuceneSearchHit hit) {
            this.hit = hit;
        }

        private LuceneSearchHit withScore(float score) {
            return new LuceneSearchHit(
                hit.docId(),
                hit.fileName(),
                hit.section(),
                hit.chunkType(),
                hit.chunkId(),
                hit.chunkIndex(),
                hit.chunkText(),
                hit.positionRatio(),
                score,
                hit.tenantId(),
                hit.userId(),
                hit.visibility(),
                hit.permissionRoles()
            );
        }
    }
}
