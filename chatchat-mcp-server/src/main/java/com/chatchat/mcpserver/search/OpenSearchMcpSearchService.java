package com.chatchat.mcpserver.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

@Service
@Slf4j
public class OpenSearchMcpSearchService {

    private static final String TEMPLATE_INDEX = "templates";
    private static final String ASSET_INDEX_PREFIX = "assets-";
    private static final String DATABASE_QUERY_TEMPLATE_INDEX = "database-query-templates";
    private static final String API_SERVICE_TEMPLATE_INDEX = "api-service-templates";
    private static final List<String> KNOWN_ASSET_TYPES = List.of("ssh_host", "sql_datasource", "http_endpoint", "api_service");

    private static final String FIELD_ID = "id";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_ASSET_TYPE = "assetType";
    private static final String FIELD_ENV = "env";
    private static final String FIELD_DB_TYPE = "dbType";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_NAME_TEXT = "nameText";
    private static final String FIELD_INTENT_TEXT = "intentText";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_RISK_LEVEL = "riskLevel";
    private static final String FIELD_RESULT_ID = "resultId";
    private static final String FIELD_DATABASE = "database";
    private static final String FIELD_TABLE = "table";
    private static final String FIELD_FULL_PATH = "fullPath";
    private static final String FIELD_TABLE_COMMENT = "tableComment";
    private static final String FIELD_DATABASE_COMMENT = "databaseComment";
    private static final String MCP_NGRAM_ANALYZER = "chatchat_mcp_ngram";
    private static final String MCP_IK_INDEX_ANALYZER = "chatchat_mcp_ik_index";
    private static final String MCP_IK_SEARCH_ANALYZER = "chatchat_mcp_ik_search";
    private static final String MCP_PINYIN_ANALYZER = "chatchat_mcp_pinyin";
    private static final int BULK_BATCH_SIZE = 10;

    private final LuceneSearchProperties properties;
    private final McpEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper;
    private final OpenSearchSearchBulkhead lexicalSearchBulkhead;
    private final OpenSearchSearchBulkhead vectorSearchBulkhead;
    private final Set<String> vectorCompatibilityWarnings = ConcurrentHashMap.newKeySet();
    private volatile RestClient restClient;

    @Autowired
    public OpenSearchMcpSearchService(LuceneSearchProperties properties,
                                      McpEmbeddingClient embeddingClient,
                                      ObjectMapper objectMapper) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        LuceneSearchProperties.OpenSearch.SearchConcurrency concurrency = searchConcurrencyConfig();
        this.lexicalSearchBulkhead = new OpenSearchSearchBulkhead(
            "lexical", concurrency.isEnabled(), concurrency.getLexical());
        this.vectorSearchBulkhead = new OpenSearchSearchBulkhead(
            "vector", concurrency.isEnabled(), concurrency.getVector());
    }

    public OpenSearchMcpSearchService(LuceneSearchProperties properties, McpEmbeddingClient embeddingClient) {
        this(properties, embeddingClient, new ObjectMapper());
    }

    public boolean enabled() {
        return properties != null
            && properties.isEnabled()
            && properties.isOpenSearchEngine()
            && properties.getOpenSearch() != null
            && properties.getOpenSearch().isEnabled();
    }

    public void indexTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        rebuild(TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public void upsertTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        upsert(TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public List<LuceneMcpSearchService.SearchHit> searchTemplates(LuceneMcpSearchService.TemplateSearchRequest request) {
        return search(TEMPLATE_INDEX, templateQueryBody(request), request.limit(), request.intentText(), templateFilterBody(request),
            generatedSearchRequestId());
    }

    public void indexDatabaseQueryTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        rebuild(DATABASE_QUERY_TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public void upsertDatabaseQueryTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        upsert(DATABASE_QUERY_TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public List<LuceneMcpSearchService.SearchHit> searchDatabaseQueryTemplates(LuceneMcpSearchService.TemplateSearchRequest request) {
        return search(DATABASE_QUERY_TEMPLATE_INDEX, templateQueryBody(request), request.limit(), request.intentText(),
            templateFilterBody(request), generatedSearchRequestId());
    }

    public void indexApiServiceTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        rebuild(API_SERVICE_TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public void upsertApiServiceTemplates(List<LuceneMcpSearchService.TemplateDoc> docs) {
        upsert(API_SERVICE_TEMPLATE_INDEX, safeTemplateDocs(docs).stream().map(this::templateSource).toList());
    }

    public List<LuceneMcpSearchService.SearchHit> searchApiServiceTemplates(LuceneMcpSearchService.TemplateSearchRequest request) {
        return search(API_SERVICE_TEMPLATE_INDEX, templateQueryBody(request), request.limit(), request.intentText(),
            templateFilterBody(request), generatedSearchRequestId());
    }

    public void indexAssets(List<LuceneMcpSearchService.AssetDoc> docs) {
        Map<String, List<LuceneMcpSearchService.AssetDoc>> docsByAssetType = safeAssetDocs(docs).stream()
            .filter(doc -> normalizeAssetType(doc.assetType()) != null)
            .collect(Collectors.groupingBy(
                doc -> normalizeAssetType(doc.assetType()),
                LinkedHashMap::new,
                Collectors.toList()
            ));
        for (Map.Entry<String, List<LuceneMcpSearchService.AssetDoc>> entry : docsByAssetType.entrySet()) {
            rebuild(assetIndexName(entry.getKey()), entry.getValue().stream().map(this::assetSource).toList());
        }
    }

    public void indexAssets(String assetType, List<LuceneMcpSearchService.AssetDoc> docs) {
        String normalizedAssetType = normalizeAssetType(assetType);
        if (normalizedAssetType == null) {
            log.warn("MCP OpenSearch typed asset index rebuild skipped because assetType is blank");
            return;
        }
        rebuild(assetIndexName(normalizedAssetType), safeAssetDocs(docs).stream()
            .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
            .map(this::assetSource)
            .toList());
    }

    public void upsertAssets(List<LuceneMcpSearchService.AssetDoc> docs) {
        Map<String, List<LuceneMcpSearchService.AssetDoc>> docsByAssetType = safeAssetDocs(docs).stream()
            .filter(doc -> normalizeAssetType(doc.assetType()) != null)
            .collect(Collectors.groupingBy(
                doc -> normalizeAssetType(doc.assetType()),
                LinkedHashMap::new,
                Collectors.toList()
            ));
        for (Map.Entry<String, List<LuceneMcpSearchService.AssetDoc>> entry : docsByAssetType.entrySet()) {
            upsert(assetIndexName(entry.getKey()), entry.getValue().stream().map(this::assetSource).toList());
        }
    }

    public void upsertAssets(String assetType, List<LuceneMcpSearchService.AssetDoc> docs) {
        String normalizedAssetType = normalizeAssetType(assetType);
        if (normalizedAssetType == null) {
            log.warn("MCP OpenSearch typed asset upsert skipped because assetType is blank");
            return;
        }
        upsert(assetIndexName(normalizedAssetType), safeAssetDocs(docs).stream()
            .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
            .map(this::assetSource)
            .toList());
    }

    public List<LuceneMcpSearchService.SearchHit> searchAssets(LuceneMcpSearchService.AssetSearchRequest request) {
        LuceneMcpSearchService.AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        String assetType = normalizeAssetType(effectiveRequest.assetType());
        String searchRequestId = firstText(effectiveRequest.searchRequestId(), generatedSearchRequestId());
        if (assetType != null) {
            return search(assetIndexName(assetType), assetQueryBody(effectiveRequest), effectiveRequest.limit(),
                effectiveRequest.queryText(), assetFilterBody(effectiveRequest), searchRequestId);
        }
        List<LuceneMcpSearchService.SearchHit> hits = new ArrayList<>();
        int limit = effectiveRequest.limit();
        Map<String, Object> query = assetQueryBody(effectiveRequest);
        Map<String, Object> vectorFilter = assetFilterBody(effectiveRequest);
        for (String knownAssetType : KNOWN_ASSET_TYPES) {
            hits.addAll(search(assetIndexName(knownAssetType), query, limit, effectiveRequest.queryText(), vectorFilter,
                searchRequestId));
        }
        return hits.stream()
            .sorted(Comparator.comparingDouble(LuceneMcpSearchService.SearchHit::score).reversed())
            .limit(Math.max(1, Math.min(maxResults(), limit)))
            .toList();
    }

    public boolean logicalIndexExists(String indexName) {
        if (!enabled() || indexName == null || indexName.isBlank()) {
            return false;
        }
        try {
            return physicalIndexExists(openSearchIndexName(indexName));
        } catch (Exception ex) {
            log.warn("MCP OpenSearch index existence check failed index={} error={}", indexName, ex.getMessage());
            return false;
        }
    }

    @PreDestroy
    public void close() {
        RestClient current = restClient;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException ex) {
            log.warn("Failed to close MCP OpenSearch client url={} error={}", baseUrl(), ex.getMessage());
        }
    }

    private synchronized void rebuild(String indexName, List<Map<String, Object>> docs) {
        String index = openSearchIndexName(indexName);
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP OpenSearch index rebuild start index={} docs={} vectorEnabled={}",
            index, count, embeddingClient.configured());
        ensureIndex(index);
        request("POST", "/" + index + "/_delete_by_query?conflicts=proceed&refresh=true",
            Map.of("query", Map.of("match_all", Map.of())), true);
        int written = bulk(index, docs);
        log.info("MCP OpenSearch index rebuild finished index={} docs={} written={} durationMs={}",
            index, count, written, System.currentTimeMillis() - started);
    }

    private synchronized void upsert(String indexName, List<Map<String, Object>> docs) {
        String index = openSearchIndexName(indexName);
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP OpenSearch index upsert start index={} docs={} vectorEnabled={}",
            index, count, embeddingClient.configured());
        ensureIndex(index);
        int written = bulk(index, docs);
        log.info("MCP OpenSearch index upsert finished index={} docs={} written={} durationMs={}",
            index, count, written, System.currentTimeMillis() - started);
    }

    private int bulk(String index, List<Map<String, Object>> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        StringBuilder body = new StringBuilder();
        boolean vectorEnabled = embeddingClient.configured();
        boolean vectorWritable = vectorEnabled && vectorSearchAvailable(index);
        if (!vectorEnabled) {
            log.info("MCP OpenSearch vector indexing skipped index={} reason=embedding-disabled", index);
        } else if (!vectorWritable) {
            log.info("MCP OpenSearch vector indexing skipped index={} reason=index-vector-unavailable", index);
        }
        int written = 0;
        int vectorized = 0;
        int processed = 0;
        int total = docs.size();
        List<Map<String, Object>> batch = new ArrayList<>(BULK_BATCH_SIZE);
        for (Map<String, Object> source : docs) {
            processed++;
            String id = String.valueOf(source.getOrDefault(FIELD_ID, ""));
            if (id.isBlank()) {
                continue;
            }
            batch.add(new LinkedHashMap<>(source));
            if (batch.size() >= BULK_BATCH_SIZE) {
                int[] counts = appendBatch(body, index, batch, vectorWritable);
                written += counts[0];
                vectorized += counts[1];
                flushBulk(index, body);
                if (vectorEnabled) {
                    log.info("MCP OpenSearch bulk progress index={} processed={}/{} written={} vectorized={} vectorWritable={}",
                        index, processed, total, written, vectorized, vectorWritable);
                } else {
                    log.info("MCP OpenSearch bulk progress index={} processed={}/{} written={}",
                        index, processed, total, written);
                }
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            int[] counts = appendBatch(body, index, batch, vectorWritable);
            written += counts[0];
            vectorized += counts[1];
        }
        if (body.length() > 0) {
            flushBulk(index, body);
        }
        if (vectorEnabled) {
            log.info("MCP OpenSearch bulk vector status index={} vectorWritable={} vectorized={} docs={}",
                index, vectorWritable, vectorized, written);
        }
        return written;
    }

    private int[] appendBatch(StringBuilder body, String index, List<Map<String, Object>> sources, boolean vectorWritable) {
        int vectorized = 0;
        if (vectorWritable) {
            List<List<Float>> vectors = embeddingClient.embedAll(sources.stream().map(this::embeddingInput).toList());
            for (int i = 0; i < Math.min(sources.size(), vectors.size()); i++) {
                List<Float> vector = vectors.get(i);
                if (!vector.isEmpty()) {
                    sources.get(i).put(vectorField(), vector);
                    vectorized++;
                }
            }
        }
        int written = 0;
        for (Map<String, Object> source : sources) {
            String id = String.valueOf(source.getOrDefault(FIELD_ID, ""));
            if (id.isBlank()) {
                continue;
            }
            body.append(json(Map.of("index", Map.of("_index", index, "_id", id)))).append('\n');
            body.append(json(source)).append('\n');
            written++;
        }
        return new int[] {written, vectorized};
    }

    private void flushBulk(String index, StringBuilder body) {
        if (body == null || body.length() == 0) {
            return;
        }
        JsonNode response = requestRaw("POST", "/_bulk?refresh=true", body.toString(), false, "application/x-ndjson");
        body.setLength(0);
        if (response != null && response.path("errors").asBoolean(false)) {
            JsonNode items = response.path("items");
            JsonNode firstItem = items.isArray() && !items.isEmpty() ? items.path(0) : items;
            log.warn("MCP OpenSearch bulk completed with item errors index={} firstItem={}", index, firstItem);
        }
    }

    private List<LuceneMcpSearchService.SearchHit> search(String indexName, Map<String, Object> query, int limit,
                                                          String semanticText, Map<String, Object> vectorFilter,
                                                          String searchRequestId) {
        String index = openSearchIndexName(indexName);
        if (!physicalIndexExists(index)) {
            return List.of();
        }
        int resultLimit = Math.max(1, Math.min(maxResults(), limit));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", resultLimit);
        body.put("query", query == null ? Map.of("match_all", Map.of()) : query);
        long startedAt = System.nanoTime();
        JsonNode lexicalRoot;
        try {
            lexicalRoot = lexicalSearchBulkhead.execute(
                () -> searchRequest("POST", "/" + index + "/_search", body));
        } catch (OpenSearchSearchBulkhead.SearchRejectedException ex) {
            log.warn("MCP OpenSearch search busy searchRequestId={} phase={} index={} waiting={} reason={}",
                searchRequestId, ex.bulkhead(), index, lexicalSearchBulkhead.waitingCount(), ex.getMessage());
            return List.of();
        }
        debugSearch(searchRequestId, "lexical", index, body, lexicalRoot, elapsedMs(startedAt));
        List<LuceneMcpSearchService.SearchHit> lexicalHits = parseHits(lexicalRoot, "opensearch_bm25");
        List<LuceneMcpSearchService.SearchHit> vectorHits = searchVector(index, semanticText, resultLimit, vectorFilter,
            searchRequestId);
        return vectorHits.isEmpty() ? lexicalHits : mergeHits(lexicalHits, vectorHits, resultLimit);
    }

    private List<LuceneMcpSearchService.SearchHit> searchVector(String index, String semanticText, int limit,
                                                                Map<String, Object> vectorFilter,
                                                                String searchRequestId) {
        String text = normalizeText(semanticText);
        if (!embeddingClient.enabled() || text == null || !vectorSearchAvailable(index)) {
            return List.of();
        }
        try {
            return vectorSearchBulkhead.execute(() -> {
                List<Float> vector = embeddingClient.embed(text);
                if (vector.isEmpty()) {
                    return List.of();
                }
                int vectorLimit = Math.max(limit, Math.max(1, embeddingConfig().getVectorCandidateLimit()));
                Map<String, Object> knnOptions = new LinkedHashMap<>();
                knnOptions.put("vector", vector);
                knnOptions.put("k", vectorLimit);
                if (vectorFilter != null && !vectorFilter.containsKey("match_all")) {
                    knnOptions.put("filter", vectorFilter);
                }
                Map<String, Object> body = Map.of(
                    "size", vectorLimit,
                    "query", Map.of("knn", Map.of(vectorField(), knnOptions))
                );
                long startedAt = System.nanoTime();
                JsonNode vectorRoot = searchRequest("POST", "/" + index + "/_search", body);
                debugSearch(searchRequestId, "vector", index, body, vectorRoot, elapsedMs(startedAt));
                return parseHits(vectorRoot, "opensearch_vector");
            });
        } catch (OpenSearchSearchBulkhead.SearchRejectedException ex) {
            log.info("MCP OpenSearch vector search degraded to BM25 searchRequestId={} index={} waiting={} reason={}",
                searchRequestId, index, vectorSearchBulkhead.waitingCount(), ex.getMessage());
            return List.of();
        } catch (Exception ex) {
            log.warn("MCP OpenSearch vector search failed searchRequestId={} index={} error={}",
                searchRequestId, index, ex.getMessage());
            return List.of();
        }
    }

    private void debugSearch(String searchRequestId, String phase, String index, Map<String, Object> body,
                             JsonNode response, long durationMs) {
        if (!searchDebugEnabled() && !log.isDebugEnabled()) {
            return;
        }
        log.info("MCP OpenSearch search debug searchRequestId={} phase={} index={} durationMs={} dsl={}",
            searchRequestId, phase, index, durationMs, safeJson(body));
        JsonNode hits = response == null ? null : response.path("hits");
        String total = hits == null || hits.isMissingNode() ? "unknown" : hits.path("total").toString();
        log.info("MCP OpenSearch search debug searchRequestId={} phase={} index={} durationMs={} hitsTotal={} hitsSample={}",
            searchRequestId, phase, index, durationMs, total, hitsSample(hits));
    }

    private boolean searchDebugEnabled() {
        return openSearchConfig().isDebugSearch();
    }

    private String hitsSample(JsonNode hits) {
        if (hits == null || hits.isMissingNode()) {
            return "[]";
        }
        JsonNode nodes = hits.path("hits");
        if (!nodes.isArray()) {
            return "[]";
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        int count = 0;
        for (JsonNode hitNode : nodes) {
            if (count++ >= 3) {
                break;
            }
            JsonNode source = hitNode.path("_source");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("_id", text(hitNode, "_id"));
            item.put("_index", text(hitNode, "_index"));
            item.put("_score", hitNode.path("_score").asDouble(0.0D));
            item.put("source", text(source, "source"));
            item.put("id", text(source, FIELD_ID));
            item.put("resultId", text(source, FIELD_RESULT_ID));
            item.put("assetType", text(source, FIELD_ASSET_TYPE));
            item.put("env", text(source, FIELD_ENV));
            item.put("dbType", text(source, FIELD_DB_TYPE));
            item.put("database", text(source, FIELD_DATABASE));
            item.put("table", text(source, FIELD_TABLE));
            item.put("tableComment", text(source, FIELD_TABLE_COMMENT));
            sample.add(compactMap(item));
        }
        return safeJson(sample);
    }

    private List<LuceneMcpSearchService.SearchHit> parseHits(JsonNode root, String scoreReason) {
        JsonNode nodes = root.path("hits").path("hits");
        if (!nodes.isArray()) {
            return List.of();
        }
        List<LuceneMcpSearchService.SearchHit> hits = new ArrayList<>();
        for (JsonNode hitNode : nodes) {
            JsonNode source = hitNode.path("_source");
            String sourceName = text(source, "source");
            float score = (float) hitNode.path("_score").asDouble(0.0D);
            hits.add(new LuceneMcpSearchService.SearchHit(
                firstText(text(source, FIELD_RESULT_ID), text(source, FIELD_ID)),
                text(source, FIELD_KIND),
                score,
                sourceName == null || sourceName.isBlank()
                    ? List.of(scoreReason + ":" + round(score))
                    : List.of(scoreReason + ":" + round(score), "source:" + sourceName),
                text(source, FIELD_ID),
                sourceName,
                text(source, FIELD_RESULT_ID),
                text(source, FIELD_DATABASE),
                text(source, FIELD_TABLE),
                text(source, FIELD_FULL_PATH),
                text(source, FIELD_TABLE_COMMENT),
                text(source, FIELD_DATABASE_COMMENT),
                text(source, FIELD_ASSET_TYPE),
                text(source, FIELD_NAME),
                text(source, FIELD_DESCRIPTION),
                text(source, FIELD_CATEGORY),
                text(source, FIELD_DB_TYPE),
                text(source, FIELD_RISK_LEVEL)
            ));
        }
        return hits;
    }

    private List<LuceneMcpSearchService.SearchHit> mergeHits(List<LuceneMcpSearchService.SearchHit> lexicalHits,
                                                             List<LuceneMcpSearchService.SearchHit> vectorHits,
                                                             int limit) {
        Map<String, MergedSearchHit> merged = new LinkedHashMap<>();
        float maxBm25 = maxScore(lexicalHits);
        float maxVector = maxScore(vectorHits);
        LuceneSearchProperties.OpenSearch.Embedding config = embeddingConfig();
        for (LuceneMcpSearchService.SearchHit hit : lexicalHits) {
            MergedSearchHit mergedHit = merged.computeIfAbsent(mergeKey(hit), ignored -> new MergedSearchHit(hit));
            mergedHit.bm25 = maxBm25 <= 0 ? 0.0F : hit.score() / maxBm25;
            mergedHit.reasons.addAll(hit.reasons());
        }
        for (LuceneMcpSearchService.SearchHit hit : vectorHits) {
            MergedSearchHit mergedHit = merged.computeIfAbsent(mergeKey(hit), ignored -> new MergedSearchHit(hit));
            mergedHit.vector = maxVector <= 0 ? 0.0F : hit.score() / maxVector;
            mergedHit.reasons.addAll(hit.reasons());
        }
        return merged.values().stream()
            .map(hit -> hit.toSearchHit(config.getBm25Weight(), config.getVectorWeight()))
            .sorted(Comparator.comparingDouble(LuceneMcpSearchService.SearchHit::score).reversed())
            .limit(limit)
            .toList();
    }

    private void ensureIndex(String index) {
        if (physicalIndexExists(index)) {
            if (embeddingClient.configured()) {
                vectorSearchAvailable(index);
            }
            return;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("index.max_ngram_diff", 13);
        settings.put("analysis", analysisSettings());
        if (embeddingClient.configured()) {
            settings.put("index.knn", true);
        }
        Map<String, Object> mappings = new LinkedHashMap<>(Map.ofEntries(
            Map.entry(FIELD_ID, Map.of("type", "keyword")),
            Map.entry(FIELD_KIND, Map.of("type", "keyword")),
            Map.entry(FIELD_ASSET_TYPE, Map.of("type", "keyword")),
            Map.entry(FIELD_ENV, Map.of("type", "keyword")),
            Map.entry(FIELD_DB_TYPE, Map.of("type", "keyword")),
            Map.entry(FIELD_LABEL, Map.of("type", "keyword")),
            Map.entry(FIELD_RESULT_ID, Map.of("type", "keyword")),
            Map.entry(FIELD_DATABASE, Map.of("type", "keyword")),
            Map.entry(FIELD_TABLE, Map.of("type", "keyword")),
            Map.entry(FIELD_FULL_PATH, Map.of("type", "keyword")),
            Map.entry(FIELD_RISK_LEVEL, Map.of("type", "keyword")),
            Map.entry(FIELD_NAME, chineseTextMapping(false)),
            Map.entry(FIELD_DESCRIPTION, chineseTextMapping(false)),
            Map.entry(FIELD_CATEGORY, chineseTextMapping(false)),
            Map.entry(FIELD_TABLE_COMMENT, chineseTextMapping(false)),
            Map.entry(FIELD_DATABASE_COMMENT, chineseTextMapping(false)),
            Map.entry(FIELD_NAME_TEXT, textMapping()),
            Map.entry(FIELD_INTENT_TEXT, textMapping()),
            Map.entry(FIELD_TEXT, textMapping()),
            Map.entry("source", Map.of("type", "keyword"))
        ));
        if (embeddingClient.configured()) {
            mappings.put(vectorField(), Map.of(
                "type", "knn_vector",
                "dimension", Math.max(1, embeddingConfig().getDimension())
            ));
        }
        try {
            request("PUT", "/" + index, Map.of("settings", settings, "mappings", Map.of("properties", mappings)), false);
            log.info("MCP OpenSearch index created index={} vectorEnabled={} vectorField={} dimension={}",
                index, embeddingClient.configured(), vectorField(), embeddingConfig().getDimension());
        } catch (Exception ex) {
            if (!embeddingClient.configured()) {
                throw ex;
            }
            log.warn("MCP OpenSearch KNN index creation failed index={} error={}. Falling back to lexical index.",
                index, ex.getMessage());
            settings.remove("index.knn");
            mappings.remove(vectorField());
            request("PUT", "/" + index, Map.of("settings", settings, "mappings", Map.of("properties", mappings)), false);
            log.info("MCP OpenSearch index created index={} vectorEnabled=false", index);
        }
    }

    private Map<String, Object> assetQueryBody(LuceneMcpSearchService.AssetSearchRequest request) {
        LuceneMcpSearchService.AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        List<Object> must = new ArrayList<>();
        addExactFilter(must, FIELD_ASSET_TYPE, effectiveRequest.assetType());
        addExactFilter(must, FIELD_ENV, effectiveRequest.env());
        addExactFilter(must, FIELD_DB_TYPE, effectiveRequest.dbType());
        for (String label : effectiveRequest.labels()) {
            addExactFilter(must, FIELD_LABEL, label);
        }
        String queryText = normalizeText(effectiveRequest.queryText());
        if (queryText != null) {
            String exact = normalizeExact(queryText);
            List<Object> should = new ArrayList<>();
            should.add(Map.of("multi_match", Map.of(
                "query", queryText,
                "fields", List.of(
                    FIELD_NAME_TEXT + "^2.4",
                    FIELD_NAME_TEXT + ".ngram^1.6",
                    FIELD_NAME_TEXT + ".pinyin^1.8",
                    FIELD_TEXT,
                    FIELD_TEXT + ".ngram^1.1",
                    FIELD_TEXT + ".pinyin^1.2",
                    FIELD_TABLE + "^3.0",
                    FIELD_FULL_PATH + "^4.0"
                ),
                "operator", "or"
            )));
            should.add(Map.of("term", Map.of(FIELD_ID, Map.of("value", exact, "boost", 3.0))));
            should.add(Map.of("term", Map.of(FIELD_RESULT_ID, Map.of("value", exact, "boost", 3.0))));
            should.add(Map.of("term", Map.of(FIELD_FULL_PATH, Map.of("value", exact, "boost", 4.0))));
            should.add(Map.of("term", Map.of(FIELD_TABLE, Map.of("value", exact, "boost", 3.0))));
            must.add(Map.of("bool", Map.of("should", should, "minimum_should_match", 1)));
        }
        return must.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("must", must));
    }

    private Map<String, Object> assetFilterBody(LuceneMcpSearchService.AssetSearchRequest request) {
        LuceneMcpSearchService.AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        List<Object> filter = new ArrayList<>();
        addExactFilter(filter, FIELD_ASSET_TYPE, effectiveRequest.assetType());
        addExactFilter(filter, FIELD_ENV, effectiveRequest.env());
        addExactFilter(filter, FIELD_DB_TYPE, effectiveRequest.dbType());
        for (String label : effectiveRequest.labels()) {
            addExactFilter(filter, FIELD_LABEL, label);
        }
        return filter.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("filter", filter));
    }

    private Map<String, Object> templateQueryBody(LuceneMcpSearchService.TemplateSearchRequest request) {
        List<Object> must = new ArrayList<>();
        addExactFilter(must, FIELD_ASSET_TYPE, request.assetType());
        if (request.dbType() != null && !request.dbType().isBlank()) {
            must.add(Map.of("bool", Map.of(
                "should", List.of(
                    Map.of("term", Map.of(FIELD_DB_TYPE, normalizeExact(request.dbType()))),
                    Map.of("term", Map.of(FIELD_DB_TYPE, "generic"))
                ),
                "minimum_should_match", 1
            )));
        }
        String queryText = normalizeText(request.intentText());
        if (queryText != null) {
            must.add(Map.of("multi_match", Map.of(
                "query", queryText,
                "fields", List.of(
                    FIELD_INTENT_TEXT + "^2.4",
                    FIELD_INTENT_TEXT + ".ngram^1.5",
                    FIELD_INTENT_TEXT + ".pinyin^1.6",
                    FIELD_TEXT,
                    FIELD_TEXT + ".ngram^1.1",
                    FIELD_TEXT + ".pinyin^1.2"
                ),
                "operator", "or"
            )));
        }
        return must.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("must", must));
    }

    private Map<String, Object> templateFilterBody(LuceneMcpSearchService.TemplateSearchRequest request) {
        List<Object> filter = new ArrayList<>();
        addExactFilter(filter, FIELD_ASSET_TYPE, request.assetType());
        if (request.dbType() != null && !request.dbType().isBlank()) {
            filter.add(Map.of("bool", Map.of(
                "should", List.of(
                    Map.of("term", Map.of(FIELD_DB_TYPE, normalizeExact(request.dbType()))),
                    Map.of("term", Map.of(FIELD_DB_TYPE, "generic"))
                ),
                "minimum_should_match", 1
            )));
        }
        return filter.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("filter", filter));
    }

    private void addExactFilter(List<Object> filters, String field, String value) {
        if (FIELD_ENV.equals(field)) {
            List<String> variants = exactFilterVariants(value, true);
            if (variants.isEmpty()) {
                return;
            }
            if (variants.size() == 1) {
                filters.add(Map.of("term", Map.of(field, variants.get(0))));
                return;
            }
            filters.add(Map.of("bool", Map.of(
                "should", variants.stream()
                    .map(item -> Map.of("term", Map.of(field, item)))
                    .toList(),
                "minimum_should_match", 1
            )));
            return;
        }
        String normalized = normalizeExact(value);
        if (normalized != null) {
            filters.add(Map.of("term", Map.of(field, normalized)));
        }
    }

    private List<String> exactFilterVariants(String value, boolean includeUppercase) {
        String text = value == null || value.isBlank() ? null : value.trim();
        if (text == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        addVariant(values, text);
        addVariant(values, normalizeExact(text));
        if (includeUppercase) {
            addVariant(values, text.toUpperCase(Locale.ROOT));
        }
        return values;
    }

    private void addVariant(List<String> values, String value) {
        if (value == null || value.isBlank() || values.contains(value)) {
            return;
        }
        values.add(value);
    }

    private Map<String, Object> assetSource(LuceneMcpSearchService.AssetDoc doc) {
        Map<String, Object> source = baseSource("asset", doc.id());
        put(source, FIELD_ASSET_TYPE, doc.assetType());
        put(source, FIELD_ENV, doc.env());
        put(source, FIELD_DB_TYPE, doc.dbType());
        put(source, FIELD_RESULT_ID, doc.resultId());
        put(source, FIELD_DATABASE, doc.databaseName());
        put(source, FIELD_TABLE, doc.tableName());
        put(source, FIELD_FULL_PATH, doc.fullPath());
        put(source, FIELD_NAME, firstText(doc.displayName(), doc.name(), doc.toolName()));
        put(source, FIELD_DESCRIPTION, firstText(doc.extraText(), doc.fullPath(), doc.databaseComment(), doc.tableComment()));
        put(source, FIELD_TABLE_COMMENT, doc.tableComment());
        put(source, FIELD_DATABASE_COMMENT, doc.databaseComment());
        put(source, FIELD_NAME_TEXT, join(doc.name(), doc.displayName(), doc.toolName(), doc.databaseName(), doc.tableName(),
            doc.fullPath(), doc.extraText(), doc.tableComment(), doc.databaseComment()));
        put(source, FIELD_TEXT, join(doc.name(), doc.displayName(), doc.toolName(), doc.databaseName(), doc.tableName(),
            doc.fullPath(), doc.extraText(), doc.tableComment(), doc.databaseComment(), String.join(" ", doc.labels())));
        put(source, "source", doc.source());
        for (String label : doc.labels()) {
            append(source, FIELD_LABEL, normalizeExact(label));
        }
        return source;
    }

    private Map<String, Object> templateSource(LuceneMcpSearchService.TemplateDoc doc) {
        Map<String, Object> source = baseSource("template", doc.id());
        put(source, FIELD_ASSET_TYPE, doc.assetType());
        put(source, FIELD_DB_TYPE, doc.dbType());
        put(source, FIELD_RISK_LEVEL, doc.riskLevel());
        put(source, FIELD_NAME, doc.name());
        put(source, FIELD_DESCRIPTION, doc.description());
        put(source, FIELD_CATEGORY, doc.category());
        put(source, FIELD_INTENT_TEXT, join(doc.intent(), doc.category(), String.join(" ", doc.intentSignals())));
        put(source, FIELD_TEXT, join(doc.id(), doc.name(), doc.description(), doc.category(), doc.intent(),
            doc.dbType(), String.join(" ", doc.intentSignals())));
        put(source, "source", doc.source());
        return source;
    }

    private Map<String, Object> baseSource(String kind, String id) {
        Map<String, Object> source = new LinkedHashMap<>();
        put(source, FIELD_ID, normalizeExact(id));
        put(source, FIELD_KIND, normalizeExact(kind));
        return source;
    }

    private void put(Map<String, Object> source, String field, String value) {
        if (value != null && !value.isBlank()) {
            source.put(field, value);
        }
    }

    private void append(Map<String, Object> source, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Object existing = source.get(field);
        if (existing == null) {
            source.put(field, value);
        } else if (existing instanceof List<?> list) {
            if (!list.contains(value)) {
                List<Object> values = new ArrayList<>(list);
                values.add(value);
                source.put(field, values);
            }
        } else if (!existing.equals(value)) {
            source.put(field, new ArrayList<>(List.of(existing, value)));
        }
    }

    private Map<String, Object> textMapping() {
        return chineseTextMapping(true);
    }

    private Map<String, Object> analysisSettings() {
        return Map.of(
            "filter", Map.of(
                "chatchat_mcp_ngram_filter", Map.of("type", "ngram", "min_gram", 3, "max_gram", 16),
                "chatchat_mcp_pinyin_filter", Map.of(
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
                MCP_NGRAM_ANALYZER, Map.of("type", "custom", "tokenizer", "standard",
                    "filter", List.of("lowercase", "asciifolding", "chatchat_mcp_ngram_filter")),
                MCP_IK_INDEX_ANALYZER, Map.of("type", "custom", "tokenizer", "ik_max_word",
                    "filter", List.of("lowercase", "icu_normalizer")),
                MCP_IK_SEARCH_ANALYZER, Map.of("type", "custom", "tokenizer", "ik_smart",
                    "filter", List.of("lowercase", "icu_normalizer")),
                MCP_PINYIN_ANALYZER, Map.of("type", "custom", "tokenizer", "keyword",
                    "filter", List.of("chatchat_mcp_pinyin_filter"))
            )
        );
    }

    private Map<String, Object> chineseTextMapping(boolean pinyinSubField) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("type", "text");
        mapping.put("analyzer", MCP_IK_INDEX_ANALYZER);
        mapping.put("search_analyzer", MCP_IK_SEARCH_ANALYZER);
        if (pinyinSubField) {
            mapping.put("fields", Map.of(
                "ngram", Map.of("type", "text", "analyzer", MCP_NGRAM_ANALYZER, "search_analyzer", "standard"),
                "pinyin", Map.of("type", "text", "analyzer", MCP_PINYIN_ANALYZER, "search_analyzer", MCP_PINYIN_ANALYZER)
            ));
        }
        return mapping;
    }

    private boolean physicalIndexExists(String index) {
        return request("HEAD", "/" + index, null, true) != null;
    }

    private boolean vectorSearchAvailable(String index) {
        if (!embeddingClient.configured()) {
            return false;
        }
        try {
            JsonNode settings = request("GET", "/" + index + "/_settings", null, false);
            boolean knnEnabled = settings.path(index).path("settings").path("index").path("knn").asBoolean(false);
            JsonNode mapping = request("GET", "/" + index + "/_mapping", null, false);
            String type = mapping.path(index).path("mappings").path("properties").path(vectorField()).path("type").asText("");
            boolean available = knnEnabled && "knn_vector".equalsIgnoreCase(type);
            if (!available && vectorCompatibilityWarnings.add(index)) {
                log.warn("MCP OpenSearch vector search disabled for index={} because index.knn={} vectorField={} type={}. "
                        + "Delete this MCP index and rebuild it to enable KNN vectors.",
                    index, knnEnabled, vectorField(), type == null || type.isBlank() ? "<missing>" : type);
            }
            return available;
        } catch (Exception ex) {
            if (vectorCompatibilityWarnings.add(index)) {
                log.warn("MCP OpenSearch vector compatibility check failed index={} error={}", index, ex.getMessage());
            }
            return false;
        }
    }

    private JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        return requestRaw(method, path, body == null ? "" : json(body), allowNotFound, "application/json");
    }

    /** Raw MCP-governed index used by internal capability modules without separate connection settings. */
    public synchronized void ensureMarketCatalogIndex(String rawIndexName) {
        if (!enabled()) return;
        String index = rawIndexName(rawIndexName);
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : List.of("title", "description", "business_description")) {
            fields.put(field, Map.of("type", "text"));
        }
        for (String field : List.of("dataset_code", "table_name", "archive_table_name", "storage_location",
            "archive_storage_location", "history_granularity", "read_tool")) {
            fields.put(field, Map.of("type", "keyword"));
        }
        if (requestRaw("HEAD", "/" + index, "", true, "application/json") == null) {
            requestRaw("PUT", "/" + index, json(Map.of("mappings", Map.of("properties", fields))),
                false, "application/json");
        } else {
            requestRaw("PUT", "/" + index + "/_mapping", json(Map.of("properties", fields)),
                false, "application/json");
        }
    }

    public void indexMarketCatalog(String rawIndexName, String datasetCode, Map<String, Object> catalog) {
        if (!enabled()) return;
        String index = rawIndexName(rawIndexName);
        Map<String, Object> document = new LinkedHashMap<>(catalog == null ? Map.of() : catalog);
        document.put("title", firstText(textValue(document.get("asset_name")), datasetCode));
        document.put("description", firstText(textValue(document.get("business_description")), ""));
        document.put("storage_location", firstText(textValue(document.get("database_name")), "") + "."
            + firstText(textValue(document.get("table_name")), ""));
        String archiveTable = firstText(textValue(document.get("archive_table_name")), "");
        document.put("archive_storage_location", archiveTable.isBlank() ? ""
            : firstText(textValue(document.get("database_name")), "") + "." + archiveTable);
        document.put("read_tool", "web_search");
        requestRaw("PUT", "/" + index + "/_doc/" + rawIndexName(datasetCode), json(document),
            false, "application/json");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchMarketCatalog(String rawIndexName, String query, int limit) {
        if (!enabled()) return List.of();
        String index = rawIndexName(rawIndexName);
        Map<String, Object> body = Map.of("size", Math.max(1, Math.min(limit, 50)), "query", Map.of("multi_match", Map.of(
            "query", query, "fields", List.of("title^4", "description^3", "business_description^2", "dataset_code"))));
        JsonNode response = searchRequest("POST", "/" + index + "/_search", body);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            result.add(objectMapper.convertValue(hit.path("_source"), Map.class));
        }
        return List.copyOf(result);
    }

    private String rawIndexName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]+", "-");
        if (normalized.isBlank()) throw new IllegalArgumentException("OpenSearch index name is blank");
        return normalized;
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public synchronized void replaceSqlDatasourceAssets(String datasourceId, List<LuceneMcpSearchService.AssetDoc> docs) {
        if (!enabled() || datasourceId == null || datasourceId.isBlank()) return;
        String index = openSearchIndexName(assetIndexName("sql_datasource"));
        ensureIndex(index);
        request("POST", "/" + index + "/_delete_by_query?conflicts=proceed&refresh=true", Map.of(
            "query", Map.of("bool", Map.of(
                "should", List.of(
                    Map.of("term", Map.of(FIELD_ID, datasourceId)),
                    Map.of("term", Map.of(FIELD_RESULT_ID, datasourceId))
                ),
                "minimum_should_match", 1
            ))
        ), true);
        bulk(index, safeAssetDocs(docs).stream().map(this::assetSource).toList());
    }

    private JsonNode searchRequest(String method, String path, Object body) {
        LuceneSearchProperties.OpenSearch.SearchConcurrency concurrency = searchConcurrencyConfig();
        int retryAttempts = Math.max(0, concurrency.getRetry429Attempts());
        for (int attempt = 0; ; attempt++) {
            try {
                return requestRaw(method, path, body == null ? "" : json(body), false, "application/json",
                    Math.max(1, concurrency.getRequestTimeoutMs()));
            } catch (IllegalStateException ex) {
                if (attempt >= retryAttempts || !isTooManyRequests(ex)) {
                    throw ex;
                }
                log.info("MCP OpenSearch search throttled path={} retryAttempt={}/{}", path, attempt + 1, retryAttempts);
                sleepBeforeRetry(concurrency);
            }
        }
    }

    private JsonNode requestRaw(String method, String path, String body, boolean allowNotFound, String contentType) {
        return requestRaw(method, path, body, allowNotFound, contentType, 0);
    }

    private JsonNode requestRaw(String method, String path, String body, boolean allowNotFound, String contentType,
                                int requestTimeoutMs) {
        try {
            Request request = new Request(method.toUpperCase(Locale.ROOT), endpointPath(path));
            applyQueryParameters(request, path);
            if (requestTimeoutMs > 0) {
                request.setOptions(RequestOptions.DEFAULT.toBuilder().setRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(Math.min(requestTimeoutMs, Math.max(1, openSearchConfig().getRequestTimeoutMs())))
                    .setConnectionRequestTimeout(requestTimeoutMs)
                    .setSocketTimeout(requestTimeoutMs)
                    .build()));
            }
            if (!body.isBlank() || !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                request.setEntity(new StringEntity(body, ContentType.create(contentType, StandardCharsets.UTF_8)));
            }
            Response response = restClient().performRequest(request);
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

    private boolean isTooManyRequests(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("status=429")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepBeforeRetry(LuceneSearchProperties.OpenSearch.SearchConcurrency concurrency) {
        int minimum = Math.max(0, concurrency.getRetryBackoffMinMs());
        int maximum = Math.max(minimum, concurrency.getRetryBackoffMaxMs());
        int delay = minimum == maximum ? minimum : ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying OpenSearch request", ex);
        }
    }

    private RestClient restClient() {
        RestClient current = restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (restClient == null) {
                restClient = buildRestClient();
            }
            return restClient;
        }
    }

    private RestClient buildRestClient() {
        URI uri = URI.create(baseUrl());
        LuceneSearchProperties.OpenSearch config = openSearchConfig();
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), port(uri), scheme(uri)))
            .setRequestConfigCallback(requestConfig -> requestConfig
                .setConnectTimeout(Math.max(1, config.getRequestTimeoutMs()))
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
            throw new IllegalStateException("Failed to create insecure MCP OpenSearch SSL context", ex);
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
        for (String pair : path.substring(queryIndex + 1).split("&")) {
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OpenSearch payload", ex);
        }
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> compactMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object item = entry.getValue();
            if (item != null && !String.valueOf(item).isBlank()) {
                result.put(entry.getKey(), item);
            }
        }
        return result;
    }

    private String generatedSearchRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private LuceneMcpSearchService.AssetSearchRequest effectiveAssetSearchRequest(LuceneMcpSearchService.AssetSearchRequest request) {
        return request == null ? new LuceneMcpSearchService.AssetSearchRequest(null, null, null, null, List.of(), maxResults()) : request;
    }

    private List<LuceneMcpSearchService.TemplateDoc> safeTemplateDocs(List<LuceneMcpSearchService.TemplateDoc> docs) {
        return docs == null ? List.of() : docs;
    }

    private List<LuceneMcpSearchService.AssetDoc> safeAssetDocs(List<LuceneMcpSearchService.AssetDoc> docs) {
        return docs == null ? List.of() : docs;
    }

    private int maxResults() {
        return properties == null ? 50 : Math.max(1, properties.getMaxResults());
    }

    private LuceneSearchProperties.OpenSearch openSearchConfig() {
        return properties == null || properties.getOpenSearch() == null
            ? new LuceneSearchProperties.OpenSearch()
            : properties.getOpenSearch();
    }

    private LuceneSearchProperties.OpenSearch.SearchConcurrency searchConcurrencyConfig() {
        LuceneSearchProperties.OpenSearch.SearchConcurrency concurrency = openSearchConfig().getSearchConcurrency();
        return concurrency == null ? new LuceneSearchProperties.OpenSearch.SearchConcurrency() : concurrency;
    }

    private LuceneSearchProperties.OpenSearch.Embedding embeddingConfig() {
        LuceneSearchProperties.OpenSearch openSearch = openSearchConfig();
        return openSearch.getEmbedding() == null ? new LuceneSearchProperties.OpenSearch.Embedding() : openSearch.getEmbedding();
    }

    private String vectorField() {
        String value = embeddingConfig().getVectorField();
        return value == null || value.isBlank() ? "mcpContentVector" : value.trim();
    }

    private String embeddingInput(Map<String, Object> source) {
        return join(
            sourceValue(source, FIELD_NAME),
            sourceValue(source, FIELD_DESCRIPTION),
            sourceValue(source, FIELD_CATEGORY),
            sourceValue(source, FIELD_DATABASE),
            sourceValue(source, FIELD_TABLE),
            sourceValue(source, FIELD_FULL_PATH),
            sourceValue(source, FIELD_TABLE_COMMENT),
            sourceValue(source, FIELD_DATABASE_COMMENT),
            sourceValue(source, FIELD_NAME_TEXT),
            sourceValue(source, FIELD_INTENT_TEXT),
            sourceValue(source, FIELD_TEXT)
        );
    }

    private String sourceValue(Map<String, Object> source, String field) {
        Object value = source == null ? null : source.get(field);
        if (value instanceof List<?> values) {
            return values.stream().map(String::valueOf).filter(text -> !text.isBlank()).collect(Collectors.joining(" "));
        }
        return value == null ? null : String.valueOf(value);
    }

    private String assetIndexName(String assetType) {
        String normalizedAssetType = normalizeAssetType(assetType);
        return normalizedAssetType == null ? "assets-unknown" : ASSET_INDEX_PREFIX + normalizedAssetType.replace('_', '-');
    }

    private String openSearchIndexName(String name) {
        String prefix = openSearchConfig().getIndexPrefix() == null ? "" : openSearchConfig().getIndexPrefix();
        return (prefix + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "-");
    }

    private String baseUrl() {
        String value = openSearchConfig().getUrl();
        String text = value == null || value.isBlank() ? "http://localhost:9200" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
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

    private float maxScore(List<LuceneMcpSearchService.SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0.0F;
        }
        float max = 0.0F;
        for (LuceneMcpSearchService.SearchHit hit : hits) {
            max = Math.max(max, hit.score());
        }
        return max;
    }

    private String mergeKey(LuceneMcpSearchService.SearchHit hit) {
        return firstText(hit.documentId(), hit.resultId(), hit.id());
    }

    private String normalizeExact(String value) {
        return normalizeText(value);
    }

    private String normalizeAssetType(String value) {
        return normalizeText(value);
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String join(String... values) {
        Set<String> parts = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String text = normalizeText(value);
                if (text != null) {
                    parts.add(text);
                }
            }
        }
        return String.join(" ", parts);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isArray() && !value.isEmpty()) {
            return value.get(0).asText();
        }
        return value.asText();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static class MergedSearchHit {
        private final LuceneMcpSearchService.SearchHit hit;
        private final List<String> reasons = new ArrayList<>();
        private float bm25;
        private float vector;

        private MergedSearchHit(LuceneMcpSearchService.SearchHit hit) {
            this.hit = hit;
        }

        private LuceneMcpSearchService.SearchHit toSearchHit(float bm25Weight, float vectorWeight) {
            float score = bm25 * bm25Weight + vector * vectorWeight;
            return new LuceneMcpSearchService.SearchHit(
                hit.id(),
                hit.kind(),
                score,
                reasons.stream().distinct().toList(),
                hit.documentId(),
                hit.source(),
                hit.resultId(),
                hit.database(),
                hit.table(),
                hit.fullPath(),
                hit.tableComment(),
                hit.databaseComment(),
                hit.assetType(),
                hit.name(),
                hit.description(),
                hit.category(),
                hit.dbType(),
                hit.riskLevel()
            );
        }
    }
}
