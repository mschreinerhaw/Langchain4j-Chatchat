package com.chatchat.mcpserver.search;

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
import org.apache.lucene.index.IndexNotFoundException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final LuceneSearchProperties properties;
    private final OpenSearchMcpSearchService openSearchSearchService;

    private final Analyzer analyzer = new NgramAnalyzer();

    @Autowired
    public LuceneMcpSearchService(LuceneSearchProperties properties,
                                  McpEmbeddingClient embeddingClient,
                                  OpenSearchMcpSearchService openSearchSearchService) {
        this.properties = properties;
        this.openSearchSearchService = openSearchSearchService == null
            ? new OpenSearchMcpSearchService(properties, embeddingClient, new ObjectMapper())
            : openSearchSearchService;
    }

    public LuceneMcpSearchService(LuceneSearchProperties properties) {
        this(properties, new McpEmbeddingClient(properties, new ObjectMapper()), null);
    }

    public boolean enabled() {
        return properties != null && properties.isEnabled()
            && (properties.isLuceneEngine() || openSearchSelected());
    }

    public List<SearchHit> searchAssets(List<AssetDoc> docs, AssetSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        AssetSearchRequest effectiveRequest = effectiveAssetSearchRequest(request);
        try {
            if (normalizeAssetType(effectiveRequest.assetType()) != null) {
                String assetType = normalizeAssetType(effectiveRequest.assetType());
                upsertAssets(assetType, safeAssetDocs(docs).stream()
                    .filter(doc -> assetType.equals(normalizeAssetType(doc.assetType())))
                    .toList());
            } else {
                upsertAssets(docs);
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
        if (openSearchSelected()) {
            openSearchSearchService.indexAssets(docs);
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
        if (openSearchSelected()) {
            openSearchSearchService.indexAssets(normalizedAssetType, docs);
            return;
        }
        try {
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
        if (openSearchSelected()) {
            openSearchSearchService.upsertAssets(docs);
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
        if (openSearchSelected()) {
            openSearchSearchService.upsertAssets(normalizedAssetType, docs);
            return;
        }
        try {
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
            if (openSearchSelected()) {
                return openSearchSearchService.searchAssets(effectiveRequest);
            }
            String assetType = normalizeAssetType(effectiveRequest.assetType());
            if (assetType != null) {
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
            switch (indexName) {
                case TEMPLATE_INDEX -> openSearchSearchService.indexTemplates(docs);
                case DATABASE_QUERY_TEMPLATE_INDEX -> openSearchSearchService.indexDatabaseQueryTemplates(docs);
                case API_SERVICE_TEMPLATE_INDEX -> openSearchSearchService.indexApiServiceTemplates(docs);
                default -> openSearchSearchService.indexTemplates(docs);
            }
            return;
        }
        rebuild(indexPath(indexName), safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
    }

    private void upsertTemplateIndex(String indexName, List<TemplateDoc> docs) throws IOException {
        if (openSearchSelected()) {
            switch (indexName) {
                case TEMPLATE_INDEX -> openSearchSearchService.upsertTemplates(docs);
                case DATABASE_QUERY_TEMPLATE_INDEX -> openSearchSearchService.upsertDatabaseQueryTemplates(docs);
                case API_SERVICE_TEMPLATE_INDEX -> openSearchSearchService.upsertApiServiceTemplates(docs);
                default -> openSearchSearchService.upsertTemplates(docs);
            }
            return;
        }
        upsert(indexPath(indexName), safeTemplateDocs(docs).stream().map(this::templateDocument).toList());
    }

    private List<SearchHit> searchTemplateIndex(String indexName, TemplateSearchRequest request) throws Exception {
        if (openSearchSelected()) {
            return switch (indexName) {
                case TEMPLATE_INDEX -> openSearchSearchService.searchTemplates(request);
                case DATABASE_QUERY_TEMPLATE_INDEX -> openSearchSearchService.searchDatabaseQueryTemplates(request);
                case API_SERVICE_TEMPLATE_INDEX -> openSearchSearchService.searchApiServiceTemplates(request);
                default -> openSearchSearchService.searchTemplates(request);
            };
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

    public boolean assetIndexExists(String assetType) {
        String normalizedAssetType = normalizeAssetType(assetType);
        return normalizedAssetType != null && namedIndexExists(assetIndexName(normalizedAssetType));
    }

    public boolean templateIndexExists() {
        return namedIndexExists(TEMPLATE_INDEX);
    }

    public boolean databaseQueryTemplateIndexExists() {
        return namedIndexExists(DATABASE_QUERY_TEMPLATE_INDEX);
    }

    public boolean apiServiceTemplateIndexExists() {
        return namedIndexExists(API_SERVICE_TEMPLATE_INDEX);
    }

    private boolean namedIndexExists(String indexName) {
        if (!enabled() || indexName == null || indexName.isBlank()) {
            return false;
        }
        if (openSearchSelected()) {
            return openSearchSearchService.logicalIndexExists(indexName);
        }
        Path path = indexPath(indexName);
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (FSDirectory directory = FSDirectory.open(path)) {
            return DirectoryReader.indexExists(directory);
        } catch (IndexNotFoundException ignored) {
            return false;
        } catch (Exception ex) {
            log.warn("MCP Lucene index existence check failed path={}: {}", path, ex.getMessage());
            return false;
        }
    }

    private Path assetIndexPath(String assetType) {
        return indexPath(assetIndexName(assetType));
    }

    private List<SearchHit> searchKnownAssetIndexes(AssetSearchRequest request) throws Exception {
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
                                     int limit,
                                     String searchRequestId) {

        public AssetSearchRequest(String assetType,
                                  String queryText,
                                  String env,
                                  String dbType,
                                  List<String> labels,
                                  int limit) {
            this(assetType, queryText, env, dbType, labels, limit, null);
        }

        public AssetSearchRequest {
            labels = labels == null ? List.of() : labels;
        }
    }

    public record TemplateSearchRequest(String assetType,
                                        String dbType,
                                        String intentText,
                                        int limit) {
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
