package com.chatchat.mcpserver.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Service
@Slf4j
public class LuceneMcpSearchService {

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
    private static final String DISABLE_HOSTNAME_VERIFICATION_PROPERTY = "jdk.internal.httpclient.disableHostnameVerification";
    private static final String MCP_NGRAM_ANALYZER = "chatchat_mcp_ngram";
    private static final String MCP_IK_INDEX_ANALYZER = "chatchat_mcp_ik_index";
    private static final String MCP_IK_SEARCH_ANALYZER = "chatchat_mcp_ik_search";
    private static final String MCP_PINYIN_ANALYZER = "chatchat_mcp_pinyin";

    private final LuceneSearchProperties properties;
    private final McpEmbeddingClient embeddingClient;

    private final Analyzer analyzer = new NgramAnalyzer();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile HttpClient httpClient;
    private final Set<String> vectorCompatibilityWarnings = ConcurrentHashMap.newKeySet();

    public LuceneMcpSearchService(LuceneSearchProperties properties, McpEmbeddingClient embeddingClient) {
        this.properties = properties;
        this.embeddingClient = embeddingClient;
    }

    public LuceneMcpSearchService(LuceneSearchProperties properties) {
        this(properties, new McpEmbeddingClient(properties, new ObjectMapper()));
    }

    public boolean enabled() {
        return properties != null && properties.isEnabled()
            && (properties.isLuceneEngine() || openSearchEnabled());
    }

    public List<SearchHit> searchAssets(List<AssetDoc> docs, AssetSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        try {
            if (normalizeAssetType(effectiveRequest.assetType()) != null) {
                String assetType = normalizeAssetType(effectiveRequest.assetType());
                indexAssets(assetType, safeAssetDocs(docs).stream()
                    .filter(doc -> assetType.equals(normalizeAssetType(doc.assetType())))
                    .toList());
            } else {
                indexAssets(docs);
            }
            return searchAssets(effectiveRequest);
        } catch (Exception ex) {
            log.warn("MCP Lucene asset search failed assetType={} queryText={} env={} dbType={} limit={}: {}",
                effectiveRequest.assetType(), effectiveRequest.queryText(), effectiveRequest.env(),
                effectiveRequest.dbType(), effectiveRequest.limit(), ex.getMessage());
            return List.of();
        }
    }

    public List<SearchHit> searchTemplates(List<TemplateDoc> docs, TemplateSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        try {
            upsertTemplates(docs);
            return searchTemplates(request);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void indexTemplates(List<TemplateDoc> docs) {
        if (!enabled()) {
            return;
        }
        try {
            rebuildTemplateIndex(TEMPLATE_INDEX, docs);
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene template index rebuild failed docs={}: {}", docs == null ? 0 : docs.size(), ex.getMessage());
        }
    }

    public void upsertTemplates(List<TemplateDoc> docs) {
        if (!enabled() || docs == null || docs.isEmpty()) {
            return;
        }
        try {
            upsertTemplateIndex(TEMPLATE_INDEX, docs);
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene template upsert failed docs={}: {}", docs.size(), ex.getMessage());
        }
    }

    public List<SearchHit> searchTemplates(TemplateSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        try {
            return searchTemplateIndex(TEMPLATE_INDEX, request);
        } catch (Exception ex) {
            log.warn("MCP Lucene template search failed assetType={} dbType={} intentText={} limit={}: {}",
                request.assetType(), request.dbType(), request.intentText(), request.limit(), ex.getMessage());
            return List.of();
        }
    }

    public void indexDatabaseQueryTemplates(List<TemplateDoc> docs) {
        indexNamedTemplateIndex(DATABASE_QUERY_TEMPLATE_INDEX, docs, "database query template");
    }

    public void upsertDatabaseQueryTemplates(List<TemplateDoc> docs) {
        upsertNamedTemplateIndex(DATABASE_QUERY_TEMPLATE_INDEX, docs, "database query template");
    }

    public List<SearchHit> searchDatabaseQueryTemplates(List<TemplateDoc> docs, TemplateSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        try {
            upsertDatabaseQueryTemplates(docs);
            return searchDatabaseQueryTemplates(request);
        } catch (Exception ex) {
            log.warn("MCP Lucene database query template search failed assetType={} dbType={} intentText={} limit={}: {}",
                request.assetType(), request.dbType(), request.intentText(), request.limit(), ex.getMessage());
            return List.of();
        }
    }

    public List<SearchHit> searchDatabaseQueryTemplates(TemplateSearchRequest request) {
        return searchNamedTemplateIndex(DATABASE_QUERY_TEMPLATE_INDEX, request, "database query template");
    }

    public void indexApiServiceTemplates(List<TemplateDoc> docs) {
        indexNamedTemplateIndex(API_SERVICE_TEMPLATE_INDEX, docs, "API service template");
    }

    public void upsertApiServiceTemplates(List<TemplateDoc> docs) {
        upsertNamedTemplateIndex(API_SERVICE_TEMPLATE_INDEX, docs, "API service template");
    }

    public List<SearchHit> searchApiServiceTemplates(TemplateSearchRequest request) {
        return searchNamedTemplateIndex(API_SERVICE_TEMPLATE_INDEX, request, "API service template");
    }

    public void indexAssets(List<AssetDoc> docs) {
        if (!enabled()) {
            return;
        }
        try {
            Map<String, List<AssetDoc>> docsByAssetType = safeAssetDocs(docs).stream()
                .filter(doc -> normalizeAssetType(doc.assetType()) != null)
                .collect(Collectors.groupingBy(
                    doc -> normalizeAssetType(doc.assetType()),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
            for (Map.Entry<String, List<AssetDoc>> entry : docsByAssetType.entrySet()) {
                if (openSearchSelected()) {
                    rebuildOpenSearch(assetIndexName(entry.getKey()), entry.getValue().stream().map(this::assetDocument).toList());
                    continue;
                }
                rebuild(assetIndexPath(entry.getKey()), entry.getValue().stream().map(this::assetDocument).toList());
            }
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene asset index rebuild failed docs={}: {}", docs == null ? 0 : docs.size(), ex.getMessage());
        }
    }

    public void indexAssets(String assetType, List<AssetDoc> docs) {
        if (!enabled()) {
            return;
        }
        String normalizedAssetType = normalizeAssetType(assetType);
        if (normalizedAssetType == null) {
            log.warn("MCP Lucene typed asset index rebuild skipped because assetType is blank");
            return;
        }
        try {
            if (openSearchSelected()) {
                rebuildOpenSearch(assetIndexName(normalizedAssetType), safeAssetDocs(docs).stream()
                    .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
                    .map(this::assetDocument)
                    .toList());
                return;
            }
            rebuild(assetIndexPath(normalizedAssetType), safeAssetDocs(docs).stream()
                .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
                .map(this::assetDocument)
                .toList());
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene {} asset index rebuild failed docs={}: {}",
                normalizedAssetType, docs == null ? 0 : docs.size(), ex.getMessage());
        }
    }

    public void upsertAssets(List<AssetDoc> docs) {
        if (!enabled() || docs == null || docs.isEmpty()) {
            return;
        }
        try {
            Map<String, List<AssetDoc>> docsByAssetType = safeAssetDocs(docs).stream()
                .filter(doc -> normalizeAssetType(doc.assetType()) != null)
                .collect(Collectors.groupingBy(
                    doc -> normalizeAssetType(doc.assetType()),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
            for (Map.Entry<String, List<AssetDoc>> entry : docsByAssetType.entrySet()) {
                if (openSearchSelected()) {
                    upsertOpenSearch(assetIndexName(entry.getKey()), entry.getValue().stream().map(this::assetDocument).toList());
                    continue;
                }
                upsert(assetIndexPath(entry.getKey()), entry.getValue().stream().map(this::assetDocument).toList());
            }
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene asset upsert failed docs={}: {}", docs.size(), ex.getMessage());
        }
    }

    public void upsertAssets(String assetType, List<AssetDoc> docs) {
        if (!enabled() || docs == null || docs.isEmpty()) {
            return;
        }
        String normalizedAssetType = normalizeAssetType(assetType);
        if (normalizedAssetType == null) {
            log.warn("MCP Lucene typed asset upsert skipped because assetType is blank");
            return;
        }
        try {
            if (openSearchSelected()) {
                upsertOpenSearch(assetIndexName(normalizedAssetType), docs.stream()
                    .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
                    .map(this::assetDocument)
                    .toList());
                return;
            }
            upsert(assetIndexPath(normalizedAssetType), docs.stream()
                .filter(doc -> normalizedAssetType.equals(normalizeAssetType(doc.assetType())))
                .map(this::assetDocument)
                .toList());
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene {} asset upsert failed docs={}: {}", normalizedAssetType, docs.size(), ex.getMessage());
        }
    }

    public List<SearchHit> searchAssets(AssetSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        try {
            String assetType = normalizeAssetType(effectiveRequest.assetType());
            if (assetType != null) {
                if (openSearchSelected()) {
                    return searchOpenSearch(assetIndexName(assetType), assetQueryBody(effectiveRequest),
                        effectiveRequest.limit(), effectiveRequest.queryText(), assetFilterBody(effectiveRequest));
                }
                return search(assetIndexPath(assetType), assetQuery(effectiveRequest), effectiveRequest.limit());
            }
            return searchKnownAssetIndexes(effectiveRequest);
        } catch (Exception ex) {
            log.warn("MCP Lucene asset search failed assetType={} queryText={} env={} dbType={} limit={}: {}",
                effectiveRequest.assetType(), effectiveRequest.queryText(), effectiveRequest.env(),
                effectiveRequest.dbType(), effectiveRequest.limit(), ex.getMessage());
            return List.of();
        }
    }

    private void indexNamedTemplateIndex(String indexName, List<TemplateDoc> docs, String label) {
        if (!enabled()) {
            return;
        }
        try {
            rebuildTemplateIndex(indexName, docs);
        } catch (Exception ex) {
            log.warn("MCP Lucene {} index rebuild failed docs={}: {}", label, docs == null ? 0 : docs.size(), ex.getMessage());
        }
    }

    private void upsertNamedTemplateIndex(String indexName, List<TemplateDoc> docs, String label) {
        if (!enabled() || docs == null || docs.isEmpty()) {
            return;
        }
        try {
            upsertTemplateIndex(indexName, docs);
        } catch (Exception ex) {
            log.warn("MCP Lucene {} upsert failed docs={}: {}", label, docs.size(), ex.getMessage());
        }
    }

    private List<SearchHit> searchNamedTemplateIndex(String indexName, TemplateSearchRequest request, String label) {
        if (!enabled()) {
            return List.of();
        }
        try {
            return searchTemplateIndex(indexName, request);
        } catch (Exception ex) {
            log.warn("MCP Lucene {} search failed assetType={} dbType={} intentText={} limit={}: {}",
                label, request.assetType(), request.dbType(), request.intentText(), request.limit(), ex.getMessage());
            return List.of();
        }
    }

    private void rebuildTemplateIndex(String indexName, List<TemplateDoc> docs) throws IOException {
        if (openSearchSelected()) {
            rebuildOpenSearch(indexName, safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
            return;
        }
        rebuild(indexPath(indexName), safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
    }

    private void upsertTemplateIndex(String indexName, List<TemplateDoc> docs) throws IOException {
        if (openSearchSelected()) {
            upsertOpenSearch(indexName, safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
            return;
        }
        upsert(indexPath(indexName), safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
    }

    private List<SearchHit> searchTemplateIndex(String indexName, TemplateSearchRequest request) throws Exception {
        if (openSearchSelected()) {
            return searchOpenSearch(indexName, templateQueryBody(request), request.limit(), request.intentText(),
                templateFilterBody(request));
        }
        return search(indexPath(indexName), templateQuery(request), request.limit());
    }

    private List<TemplateDoc> safeTemplateDocs(List<TemplateDoc> docs) {
        return docs == null ? List.of() : docs;
    }

    private List<AssetDoc> safeAssetDocs(List<AssetDoc> docs) {
        return docs == null ? List.of() : docs;
    }

    private AssetSearchRequest effectiveAssetSearchRequest(AssetSearchRequest request) {
        return request == null ? new AssetSearchRequest(null, null, null, null, List.of(), maxResults()) : request;
    }

    public String assetIndexName(String assetType) {
        String normalizedAssetType = normalizeAssetType(assetType);
        return normalizedAssetType == null ? "assets-unknown" : ASSET_INDEX_PREFIX + normalizedAssetType.replace('_', '-');
    }

    private Path assetIndexPath(String assetType) {
        return indexPath(assetIndexName(assetType));
    }

    private List<SearchHit> searchKnownAssetIndexes(AssetSearchRequest request) throws Exception {
        if (openSearchSelected()) {
            List<SearchHit> hits = new ArrayList<>();
            int limit = request.limit();
            Map<String, Object> query = assetQueryBody(request);
            Map<String, Object> vectorFilter = assetFilterBody(request);
            for (String assetType : KNOWN_ASSET_TYPES) {
                hits.addAll(searchOpenSearch(assetIndexName(assetType), query, limit, request.queryText(), vectorFilter));
            }
            return hits.stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(Math.max(1, Math.min(maxResults(), limit)))
                .toList();
        }
        List<SearchHit> hits = new ArrayList<>();
        int limit = request.limit();
        Query query = assetQuery(request);
        for (String assetType : KNOWN_ASSET_TYPES) {
            Path path = assetIndexPath(assetType);
            if (Files.exists(path)) {
                hits.addAll(search(path, query, limit));
            }
        }
        return hits.stream()
            .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
            .limit(Math.max(1, Math.min(maxResults(), limit)))
            .toList();
    }

    private Path indexPath(String name) {
        String root = properties == null || properties.getIndexDir() == null || properties.getIndexDir().isBlank()
            ? "./data/lucene/mcp"
            : properties.getIndexDir();
        return Path.of(root).resolve(name);
    }

    private synchronized void rebuild(Path path, List<Document> docs) throws IOException {
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP Lucene index rebuild start path={} docs={}", path, count);
        try (FSDirectory directory = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.deleteAll();
            for (Document doc : docs) {
                writer.updateDocument(new Term(FIELD_ID, doc.get(FIELD_ID)), doc);
            }
            writer.commit();
        }
        log.info("MCP Lucene index rebuild finished path={} docs={} durationMs={}",
            path, count, System.currentTimeMillis() - started);
    }

    private synchronized void upsert(Path path, List<Document> docs) throws IOException {
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP Lucene index upsert start path={} docs={}", path, count);
        try (FSDirectory directory = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (Document doc : docs) {
                writer.updateDocument(new Term(FIELD_ID, doc.get(FIELD_ID)), doc);
            }
            writer.commit();
        }
        log.info("MCP Lucene index upsert finished path={} docs={} durationMs={}",
            path, count, System.currentTimeMillis() - started);
    }

    private List<SearchHit> search(Path path, Query query, int limit) throws IOException {
        try (FSDirectory directory = FSDirectory.open(path);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(query, Math.max(1, Math.min(maxResults(), limit)));
            List<SearchHit> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                String source = doc.get("source");
                hits.add(new SearchHit(
                    firstText(doc.get(FIELD_RESULT_ID), doc.get(FIELD_ID)),
                    doc.get(FIELD_KIND),
                    scoreDoc.score,
                    source == null || source.isBlank()
                        ? List.of("lucene_bm25:" + round(scoreDoc.score))
                        : List.of("lucene_bm25:" + round(scoreDoc.score), "source:" + source),
                    doc.get(FIELD_ID),
                    source,
                    doc.get(FIELD_RESULT_ID),
                    doc.get(FIELD_DATABASE),
                    doc.get(FIELD_TABLE),
                    doc.get(FIELD_FULL_PATH),
                    doc.get(FIELD_TABLE_COMMENT),
                    doc.get(FIELD_DATABASE_COMMENT),
                    doc.get(FIELD_ASSET_TYPE),
                    doc.get(FIELD_NAME),
                    doc.get(FIELD_DESCRIPTION),
                    doc.get(FIELD_CATEGORY),
                    doc.get(FIELD_DB_TYPE),
                    doc.get(FIELD_RISK_LEVEL)
                ));
            }
            return hits;
        }
    }

    private synchronized void rebuildOpenSearch(String indexName, List<Document> docs) {
        String index = openSearchIndexName(indexName);
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP OpenSearch index rebuild start index={} docs={} vectorEnabled={}",
            index, count, embeddingClient.configured());
        ensureOpenSearchIndex(index);
        request("POST", "/" + index + "/_delete_by_query?conflicts=proceed&refresh=true",
            Map.of("query", Map.of("match_all", Map.of())), true);
        int written = bulkOpenSearch(index, docs);
        log.info("MCP OpenSearch index rebuild finished index={} docs={} written={} durationMs={}",
            index, count, written, System.currentTimeMillis() - started);
    }

    private synchronized void upsertOpenSearch(String indexName, List<Document> docs) {
        String index = openSearchIndexName(indexName);
        long started = System.currentTimeMillis();
        int count = docs == null ? 0 : docs.size();
        log.info("MCP OpenSearch index upsert start index={} docs={} vectorEnabled={}",
            index, count, embeddingClient.configured());
        ensureOpenSearchIndex(index);
        int written = bulkOpenSearch(index, docs);
        log.info("MCP OpenSearch index upsert finished index={} docs={} written={} durationMs={}",
            index, count, written, System.currentTimeMillis() - started);
    }

    private int bulkOpenSearch(String index, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        StringBuilder body = new StringBuilder();
        boolean vectorWritable = openSearchVectorSearchAvailable(index);
        int written = 0;
        int vectorized = 0;
        for (Document doc : docs) {
            Map<String, Object> source = sourceOf(doc);
            String id = String.valueOf(source.getOrDefault(FIELD_ID, ""));
            if (id.isBlank()) {
                continue;
            }
            if (vectorWritable) {
                List<Float> vector = embeddingClient.embed(embeddingInput(source));
                if (!vector.isEmpty()) {
                    source.put(vectorField(), vector);
                    vectorized++;
                }
            }
            body.append(json(Map.of("index", Map.of("_index", index, "_id", id)))).append('\n');
            body.append(json(source)).append('\n');
            written++;
        }
        if (body.length() > 0) {
            requestRaw("POST", "/_bulk?refresh=true", body.toString(), false, "application/x-ndjson");
        }
        if (embeddingClient.configured()) {
            log.info("MCP OpenSearch bulk vector status index={} vectorWritable={} vectorized={} docs={}",
                index, vectorWritable, vectorized, written);
        }
        return written;
    }

    private List<SearchHit> searchOpenSearch(String indexName, Map<String, Object> query, int limit,
                                             String semanticText, Map<String, Object> vectorFilter) {
        String index = openSearchIndexName(indexName);
        if (!openSearchIndexExists(index)) {
            return List.of();
        }
        int resultLimit = Math.max(1, Math.min(maxResults(), limit));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", resultLimit);
        body.put("query", query == null ? Map.of("match_all", Map.of()) : query);
        List<SearchHit> lexicalHits = parseOpenSearchHits(index, request("POST", "/" + index + "/_search", body, false),
            "opensearch_bm25");
        List<SearchHit> vectorHits = searchOpenSearchVector(index, semanticText, resultLimit, vectorFilter);
        if (vectorHits.isEmpty()) {
            return lexicalHits;
        }
        return mergeOpenSearchHits(lexicalHits, vectorHits, resultLimit);
    }

    private List<SearchHit> searchOpenSearchVector(String index, String semanticText, int limit, Map<String, Object> vectorFilter) {
        String text = normalizeText(semanticText);
        if (!embeddingClient.enabled() || text == null || !openSearchVectorSearchAvailable(index)) {
            return List.of();
        }
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
        try {
            return parseOpenSearchHits(index, request("POST", "/" + index + "/_search", body, false), "opensearch_vector");
        } catch (Exception ex) {
            log.warn("MCP OpenSearch vector search failed index={} error={}", index, ex.getMessage());
            return List.of();
        }
    }

    private List<SearchHit> parseOpenSearchHits(String index, JsonNode root, String scoreReason) {
        JsonNode nodes = root.path("hits").path("hits");
        if (!nodes.isArray()) {
            return List.of();
        }
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hitNode : nodes) {
            JsonNode source = hitNode.path("_source");
            String sourceName = text(source, "source");
            float score = (float) hitNode.path("_score").asDouble(0.0D);
            hits.add(new SearchHit(
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

    private List<SearchHit> mergeOpenSearchHits(List<SearchHit> lexicalHits, List<SearchHit> vectorHits, int limit) {
        Map<String, MergedSearchHit> merged = new LinkedHashMap<>();
        float maxBm25 = maxScore(lexicalHits);
        float maxVector = maxScore(vectorHits);
        LuceneSearchProperties.OpenSearch.Embedding config = embeddingConfig();
        for (SearchHit hit : lexicalHits) {
            String key = mergeKey(hit);
            MergedSearchHit mergedHit = merged.computeIfAbsent(key, ignored -> new MergedSearchHit(hit));
            mergedHit.bm25 = maxBm25 <= 0 ? 0.0F : hit.score() / maxBm25;
            mergedHit.reasons.addAll(hit.reasons());
        }
        for (SearchHit hit : vectorHits) {
            String key = mergeKey(hit);
            MergedSearchHit mergedHit = merged.computeIfAbsent(key, ignored -> new MergedSearchHit(hit));
            mergedHit.vector = maxVector <= 0 ? 0.0F : hit.score() / maxVector;
            mergedHit.reasons.addAll(hit.reasons());
        }
        return merged.values().stream()
            .map(hit -> hit.toSearchHit(config.getBm25Weight(), config.getVectorWeight()))
            .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
            .limit(limit)
            .toList();
    }

    private int maxResults() {
        return properties == null ? 50 : Math.max(1, properties.getMaxResults());
    }

    private Query assetQuery(AssetSearchRequest request) throws Exception {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        addExact(root, FIELD_ASSET_TYPE, request.assetType(), BooleanClause.Occur.MUST);
        addExact(root, FIELD_ENV, request.env(), BooleanClause.Occur.MUST);
        addExact(root, FIELD_DB_TYPE, request.dbType(), BooleanClause.Occur.MUST);
        for (String label : request.labels()) {
            addExact(root, FIELD_LABEL, label, BooleanClause.Occur.MUST);
        }
        String queryText = normalizeText(request.queryText());
        if (queryText != null) {
            BooleanQuery.Builder text = new BooleanQuery.Builder();
            text.add(new BoostQuery(textQuery(queryText, FIELD_NAME_TEXT, FIELD_TEXT, FIELD_TABLE, FIELD_FULL_PATH), 2.0f), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(exactQuery(FIELD_ID, queryText), 3.0f), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(exactQuery(FIELD_RESULT_ID, queryText), 3.0f), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(exactQuery(FIELD_FULL_PATH, queryText), 4.0f), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(exactQuery(FIELD_TABLE, queryText), 3.0f), BooleanClause.Occur.SHOULD);
            text.setMinimumNumberShouldMatch(1);
            root.add(text.build(), BooleanClause.Occur.MUST);
        }
        BooleanQuery built = root.build();
        return built.clauses().isEmpty() ? new MatchAllDocsQuery() : built;
    }

    private Map<String, Object> assetQueryBody(AssetSearchRequest request) {
        AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
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

    private Map<String, Object> assetFilterBody(AssetSearchRequest request) {
        AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        List<Object> filter = new ArrayList<>();
        addExactFilter(filter, FIELD_ASSET_TYPE, effectiveRequest.assetType());
        addExactFilter(filter, FIELD_ENV, effectiveRequest.env());
        addExactFilter(filter, FIELD_DB_TYPE, effectiveRequest.dbType());
        for (String label : effectiveRequest.labels()) {
            addExactFilter(filter, FIELD_LABEL, label);
        }
        return filter.isEmpty() ? Map.of("match_all", Map.of()) : Map.of("bool", Map.of("filter", filter));
    }

    private Query templateQuery(TemplateSearchRequest request) throws Exception {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        addExact(root, FIELD_ASSET_TYPE, request.assetType(), BooleanClause.Occur.MUST);
        if (request.dbType() != null && !request.dbType().isBlank()) {
            BooleanQuery.Builder dbType = new BooleanQuery.Builder();
            dbType.add(exactQuery(FIELD_DB_TYPE, request.dbType()), BooleanClause.Occur.SHOULD);
            dbType.add(exactQuery(FIELD_DB_TYPE, "generic"), BooleanClause.Occur.SHOULD);
            dbType.setMinimumNumberShouldMatch(1);
            root.add(dbType.build(), BooleanClause.Occur.MUST);
        }
        String queryText = normalizeText(request.intentText());
        if (queryText != null) {
            root.add(new BoostQuery(textQuery(queryText, FIELD_INTENT_TEXT, FIELD_TEXT), 2.0f), BooleanClause.Occur.MUST);
        }
        BooleanQuery built = root.build();
        return built.clauses().isEmpty() ? new MatchAllDocsQuery() : built;
    }

    private Map<String, Object> templateQueryBody(TemplateSearchRequest request) {
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

    private Map<String, Object> templateFilterBody(TemplateSearchRequest request) {
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

    private Query textQuery(String value, String... fields) throws Exception {
        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);
        parser.setDefaultOperator(MultiFieldQueryParser.Operator.OR);
        return parser.parse(MultiFieldQueryParser.escape(value));
    }

    private Query exactQuery(String field, String value) {
        return new TermQuery(new Term(field, normalizeExact(value)));
    }

    private void addExact(BooleanQuery.Builder builder, String field, String value, BooleanClause.Occur occur) {
        String normalized = normalizeExact(value);
        if (normalized != null) {
            builder.add(new TermQuery(new Term(field, normalized)), occur);
        }
    }

    private void addExactFilter(List<Object> filters, String field, String value) {
        String normalized = normalizeExact(value);
        if (normalized != null) {
            filters.add(Map.of("term", Map.of(field, normalized)));
        }
    }

    private Document assetDocument(AssetDoc doc) {
        Document document = baseDocument("asset", doc.id());
        addExact(document, FIELD_ASSET_TYPE, doc.assetType());
        addExact(document, FIELD_ENV, doc.env());
        addExact(document, FIELD_DB_TYPE, doc.dbType());
        addExact(document, FIELD_RESULT_ID, doc.resultId());
        addExact(document, FIELD_DATABASE, doc.databaseName());
        addExact(document, FIELD_TABLE, doc.tableName());
        addExact(document, FIELD_FULL_PATH, doc.fullPath());
        addStored(document, FIELD_ASSET_TYPE, doc.assetType());
        addStored(document, FIELD_DB_TYPE, doc.dbType());
        addStored(document, FIELD_NAME, firstText(doc.displayName(), doc.name(), doc.toolName()));
        addStored(document, FIELD_DESCRIPTION, firstText(doc.extraText(), doc.fullPath(), doc.databaseComment(), doc.tableComment()));
        addStored(document, FIELD_RESULT_ID, doc.resultId());
        addStored(document, FIELD_DATABASE, doc.databaseName());
        addStored(document, FIELD_TABLE, doc.tableName());
        addStored(document, FIELD_FULL_PATH, doc.fullPath());
        addStored(document, FIELD_TABLE_COMMENT, doc.tableComment());
        addStored(document, FIELD_DATABASE_COMMENT, doc.databaseComment());
        addText(document, FIELD_NAME_TEXT, join(doc.name(), doc.displayName(), doc.toolName(), doc.databaseName(), doc.tableName(), doc.fullPath(),
            doc.extraText(), doc.tableComment(), doc.databaseComment()));
        addText(document, FIELD_TEXT, join(doc.name(), doc.displayName(), doc.toolName(), doc.databaseName(), doc.tableName(), doc.fullPath(),
            doc.extraText(), doc.tableComment(), doc.databaseComment(), String.join(" ", doc.labels())));
        addStored(document, "source", doc.source());
        for (String label : doc.labels()) {
            addExact(document, FIELD_LABEL, label);
        }
        return document;
    }

    private Document templateDocument(TemplateDoc doc) {
        Document document = baseDocument("template", doc.id());
        addExact(document, FIELD_ASSET_TYPE, doc.assetType());
        addExact(document, FIELD_DB_TYPE, doc.dbType());
        addExact(document, FIELD_RISK_LEVEL, doc.riskLevel());
        addStored(document, FIELD_ASSET_TYPE, doc.assetType());
        addStored(document, FIELD_DB_TYPE, doc.dbType());
        addStored(document, FIELD_NAME, doc.name());
        addStored(document, FIELD_DESCRIPTION, doc.description());
        addStored(document, FIELD_CATEGORY, doc.category());
        addStored(document, FIELD_RISK_LEVEL, doc.riskLevel());
        addText(document, FIELD_INTENT_TEXT, join(doc.intent(), doc.category(), String.join(" ", doc.intentSignals())));
        addText(document, FIELD_TEXT, join(doc.id(), doc.name(), doc.description(), doc.category(), doc.intent(),
            doc.dbType(), String.join(" ", doc.intentSignals())));
        addStored(document, "source", doc.source());
        return document;
    }

    private Document baseDocument(String kind, String id) {
        Document document = new Document();
        addExact(document, FIELD_ID, id);
        addExact(document, FIELD_KIND, kind);
        addStored(document, FIELD_ID, id);
        addStored(document, FIELD_KIND, kind);
        return document;
    }

    private void addExact(Document document, String field, String value) {
        String normalized = normalizeExact(value);
        if (normalized != null) {
            document.add(new StringField(field, normalized, Field.Store.NO));
        }
    }

    private void addText(Document document, String field, String value) {
        String normalized = normalizeText(value);
        if (normalized != null) {
            document.add(new TextField(field, normalized, Field.Store.NO));
        }
    }

    private void addStored(Document document, String field, String value) {
        if (value != null && !value.isBlank()) {
            document.add(new StoredField(field, value));
        }
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

    private String normalizeExact(String value) {
        String text = normalizeText(value);
        return text == null ? null : text;
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

    private boolean openSearchSelected() {
        return properties != null && properties.isOpenSearchEngine() && openSearchEnabled();
    }

    private boolean openSearchEnabled() {
        return properties != null
            && properties.getOpenSearch() != null
            && properties.getOpenSearch().isEnabled();
    }

    private void ensureOpenSearchIndex(String index) {
        if (openSearchIndexExists(index)) {
            if (embeddingClient.configured()) {
                openSearchVectorSearchAvailable(index);
            }
            return;
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("index.max_ngram_diff", 13);
        settings.put("analysis", openSearchAnalysisSettings());
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
        Map<String, Object> body = Map.of(
            "settings", settings,
            "mappings", Map.of("properties", mappings)
        );
        try {
            request("PUT", "/" + index, body, false);
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

    private Map<String, Object> textMapping() {
        return chineseTextMapping(true);
    }

    private Map<String, Object> openSearchAnalysisSettings() {
        return Map.of(
            "filter", Map.of(
                "chatchat_mcp_ngram_filter", Map.of(
                    "type", "ngram",
                    "min_gram", 3,
                    "max_gram", 16
                ),
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
                MCP_NGRAM_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "standard",
                    "filter", List.of("lowercase", "asciifolding", "chatchat_mcp_ngram_filter")
                ),
                MCP_IK_INDEX_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "ik_max_word",
                    "filter", List.of("lowercase", "icu_normalizer")
                ),
                MCP_IK_SEARCH_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "ik_smart",
                    "filter", List.of("lowercase", "icu_normalizer")
                ),
                MCP_PINYIN_ANALYZER, Map.of(
                    "type", "custom",
                    "tokenizer", "keyword",
                    "filter", List.of("chatchat_mcp_pinyin_filter")
                )
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

    private boolean openSearchIndexExists(String index) {
        return request("HEAD", "/" + index, null, true) != null;
    }

    private Map<String, Object> sourceOf(Document document) {
        Map<String, Object> source = new LinkedHashMap<>();
        document.getFields().forEach(field -> {
            String name = field.name();
            String value = field.stringValue();
            if (value == null || value.isBlank()) {
                return;
            }
            Object existing = source.get(name);
            if (existing == null) {
                source.put(name, value);
            } else if (existing instanceof List<?> list) {
                if (!list.contains(value)) {
                    List<Object> values = new ArrayList<>(list);
                    values.add(value);
                    source.put(name, values);
                }
            } else if (!existing.equals(value)) {
                source.put(name, new ArrayList<>(List.of(existing, value)));
            }
        });
        return source;
    }

    private JsonNode request(String method, String path, Object body, boolean allowNotFound) {
        return requestRaw(method, path, body == null ? "" : json(body), allowNotFound, "application/json");
    }

    private boolean openSearchVectorSearchAvailable(String index) {
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

    private LuceneSearchProperties.OpenSearch.Embedding embeddingConfig() {
        LuceneSearchProperties.OpenSearch openSearch = properties == null ? null : properties.getOpenSearch();
        if (openSearch == null || openSearch.getEmbedding() == null) {
            return new LuceneSearchProperties.OpenSearch.Embedding();
        }
        return openSearch.getEmbedding();
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
        if (source == null || field == null) {
            return null;
        }
        Object value = source.get(field);
        if (value instanceof List<?> values) {
            return values.stream()
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining(" "));
        }
        return value == null ? null : String.valueOf(value);
    }

    private float maxScore(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0.0F;
        }
        float max = 0.0F;
        for (SearchHit hit : hits) {
            max = Math.max(max, hit.score());
        }
        return max;
    }

    private String mergeKey(SearchHit hit) {
        return firstText(hit.documentId(), hit.resultId(), hit.id());
    }

    private JsonNode requestRaw(String method, String path, String body, boolean allowNotFound, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(openSearchBaseUrl() + path))
                .timeout(Duration.ofMillis(Math.max(1, properties.getOpenSearch().getRequestTimeoutMs())))
                .header("Accept", "application/json")
                .header("Authorization", authorizationHeader());
            if (!body.isBlank() || !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                builder.header("Content-Type", contentType);
            }
            HttpRequest request = switch (method.toUpperCase(Locale.ROOT)) {
                case "GET" -> builder.GET().build();
                case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
                case "DELETE" -> builder.DELETE().build();
                default -> builder.method(method, body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build();
            };
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (allowNotFound && response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OpenSearch request failed status=" + response.statusCode()
                    + " path=" + path + " body=" + response.body());
            }
            if (response.body() == null || response.body().isBlank() || "HEAD".equalsIgnoreCase(method)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("OpenSearch request failed path=" + path + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenSearch request interrupted path=" + path, ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OpenSearch payload", ex);
        }
    }

    private String authorizationHeader() {
        String token = properties.getOpenSearch().getUsername() + ":" + properties.getOpenSearch().getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private HttpClient httpClient() {
        HttpClient current = httpClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (httpClient == null) {
                HttpClient.Builder builder = HttpClient.newBuilder();
                if (properties.getOpenSearch().isInsecureSsl()) {
                    System.setProperty(DISABLE_HOSTNAME_VERIFICATION_PROPERTY, "true");
                    builder.sslContext(insecureSslContext());
                    SSLParameters sslParameters = new SSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm("");
                    builder.sslParameters(sslParameters);
                }
                httpClient = builder.build();
            }
            return httpClient;
        }
    }

    private SSLContext insecureSslContext() {
        try {
            TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to create insecure OpenSearch SSL context", ex);
        }
    }

    private String openSearchBaseUrl() {
        String value = properties.getOpenSearch().getUrl();
        String text = value == null || value.isBlank() ? "http://localhost:9200" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private String openSearchIndexName(String name) {
        String prefix = properties.getOpenSearch().getIndexPrefix() == null ? "" : properties.getOpenSearch().getIndexPrefix();
        return (prefix + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]+", "-");
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

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public record AssetDoc(String id,
                           String assetType,
                           String name,
                           String displayName,
                           String toolName,
                           String env,
                           String dbType,
                           List<String> labels,
                           String source,
                           String resultId,
                           String databaseName,
                           String tableName,
                           String fullPath,
                           String extraText,
                           String tableComment,
                           String databaseComment) {

        public AssetDoc(String id,
                        String assetType,
                        String name,
                        String displayName,
                        String toolName,
                        String env,
                        String dbType,
                        List<String> labels,
                        String source) {
            this(id, assetType, name, displayName, toolName, env, dbType, labels, source, null, null, null, null, null, null, null);
        }

        public AssetDoc(String id,
                        String assetType,
                        String name,
                        String displayName,
                        String toolName,
                        String env,
                        String dbType,
                        List<String> labels,
                        String source,
                        String resultId,
                        String databaseName,
                        String tableName,
                        String fullPath) {
            this(id, assetType, name, displayName, toolName, env, dbType, labels, source, resultId, databaseName, tableName, fullPath, null, null, null);
        }

        public AssetDoc {
            labels = labels == null ? List.of() : labels;
        }
    }

    public record TemplateDoc(String id,
                              String assetType,
                              String name,
                              String description,
                              String category,
                              String dbType,
                              String intent,
                              String riskLevel,
                              List<String> intentSignals,
                              String source) {

        public TemplateDoc {
            intentSignals = intentSignals == null ? List.of() : intentSignals;
        }
    }

    public record AssetSearchRequest(String assetType,
                                     String queryText,
                                     String env,
                                     String dbType,
                                     List<String> labels,
                                     int limit) {

        public AssetSearchRequest {
            labels = labels == null ? List.of() : labels;
        }
    }

    public record TemplateSearchRequest(String assetType,
                                        String dbType,
                                        String intentText,
                                        int limit) {
    }

    private static class MergedSearchHit {

        private final SearchHit hit;
        private final Set<String> reasons = new LinkedHashSet<>();
        private float bm25;
        private float vector;

        private MergedSearchHit(SearchHit hit) {
            this.hit = hit;
        }

        private SearchHit toSearchHit(float bm25Weight, float vectorWeight) {
            float score = bm25 * bm25Weight + vector * vectorWeight;
            List<String> mergedReasons = new ArrayList<>(reasons);
            mergedReasons.add("hybrid_score:" + (Math.round(score * 1000.0) / 1000.0));
            return new SearchHit(
                hit.id(),
                hit.kind(),
                score,
                mergedReasons,
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

    public record SearchHit(String id,
                            String kind,
                            float score,
                            List<String> reasons,
                            String documentId,
                            String source,
                            String resultId,
                            String database,
                            String table,
                            String fullPath,
                            String tableComment,
                            String databaseComment,
                            String assetType,
                            String name,
                            String description,
                            String category,
                            String dbType,
                            String riskLevel) {

        public SearchHit(String id,
                         String kind,
                         float score,
                         List<String> reasons,
                         String documentId,
                         String source,
                         String resultId,
                         String database,
                         String table,
                         String fullPath,
                         String tableComment,
                         String databaseComment) {
            this(id, kind, score, reasons, documentId, source, resultId, database, table, fullPath,
                tableComment, databaseComment, null, null, null, null, null, null);
        }

        public SearchHit(String id, String kind, float score, List<String> reasons) {
            this(id, kind, score, reasons, id, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        }
    }

    private static class NgramAnalyzer extends Analyzer {

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            StandardTokenizer tokenizer = new StandardTokenizer();
            TokenStream stream = new LowerCaseFilter(tokenizer);
            stream = new ASCIIFoldingFilter(stream);
            stream = new NGramTokenFilter(stream, 3, 16, true);
            return new TokenStreamComponents(tokenizer, stream);
        }
    }
}
