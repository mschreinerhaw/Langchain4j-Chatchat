package com.chatchat.mcpserver.search;

import lombok.RequiredArgsConstructor;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneMcpSearchService {

    private static final String FIELD_ID = "id";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_ASSET_TYPE = "assetType";
    private static final String FIELD_ENV = "env";
    private static final String FIELD_DB_TYPE = "dbType";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_NAME_TEXT = "nameText";
    private static final String FIELD_INTENT_TEXT = "intentText";

    private final LuceneSearchProperties properties;

    private final Analyzer analyzer = new NgramAnalyzer();

    public boolean enabled() {
        return properties != null && properties.isEnabled();
    }

    public List<SearchHit> searchAssets(List<AssetDoc> docs, AssetSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        try {
            indexAssets(docs);
            return searchAssets(request);
        } catch (Exception ex) {
            log.warn("MCP Lucene asset search failed assetType={} queryText={} env={} dbType={} limit={}: {}",
                request.assetType(), request.queryText(), request.env(), request.dbType(), request.limit(), ex.getMessage());
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
            rebuild(indexPath("templates"), docs.stream().map(this::templateDocument).toList());
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
            upsert(indexPath("templates"), docs.stream().map(this::templateDocument).toList());
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
            return search(indexPath("templates"), templateQuery(request), request.limit());
        } catch (Exception ex) {
            log.warn("MCP Lucene template search failed assetType={} dbType={} intentText={} limit={}: {}",
                request.assetType(), request.dbType(), request.intentText(), request.limit(), ex.getMessage());
            return List.of();
        }
    }

    public void indexAssets(List<AssetDoc> docs) {
        if (!enabled()) {
            return;
        }
        try {
            rebuild(indexPath("assets"), docs.stream().map(this::assetDocument).toList());
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene asset index rebuild failed docs={}: {}", docs == null ? 0 : docs.size(), ex.getMessage());
        }
    }

    public void upsertAssets(List<AssetDoc> docs) {
        if (!enabled() || docs == null || docs.isEmpty()) {
            return;
        }
        try {
            upsert(indexPath("assets"), docs.stream().map(this::assetDocument).toList());
        } catch (Exception ex) {
            // Lucene is an acceleration index. Callers keep using the source registry as truth.
            log.warn("MCP Lucene asset upsert failed docs={}: {}", docs.size(), ex.getMessage());
        }
    }

    public List<SearchHit> searchAssets(AssetSearchRequest request) {
        if (!enabled()) {
            return List.of();
        }
        try {
            return search(indexPath("assets"), assetQuery(request), request.limit());
        } catch (Exception ex) {
            log.warn("MCP Lucene asset search failed assetType={} queryText={} env={} dbType={} limit={}: {}",
                request.assetType(), request.queryText(), request.env(), request.dbType(), request.limit(), ex.getMessage());
            return List.of();
        }
    }

    private Path indexPath(String name) {
        String root = properties == null || properties.getIndexDir() == null || properties.getIndexDir().isBlank()
            ? "./data/lucene/mcp"
            : properties.getIndexDir();
        return Path.of(root).resolve(name);
    }

    private synchronized void rebuild(Path path, List<Document> docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            writer.deleteAll();
            for (Document doc : docs) {
                writer.updateDocument(new Term(FIELD_ID, doc.get(FIELD_ID)), doc);
            }
            writer.commit();
        }
    }

    private synchronized void upsert(Path path, List<Document> docs) throws IOException {
        try (FSDirectory directory = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (Document doc : docs) {
                writer.updateDocument(new Term(FIELD_ID, doc.get(FIELD_ID)), doc);
            }
            writer.commit();
        }
    }

    private List<SearchHit> search(Path path, Query query, int limit) throws IOException {
        try (FSDirectory directory = FSDirectory.open(path);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs topDocs = searcher.search(query, Math.max(1, Math.min(maxResults(), limit)));
            List<SearchHit> hits = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
                hits.add(new SearchHit(
                    doc.get(FIELD_ID),
                    doc.get(FIELD_KIND),
                    scoreDoc.score,
                    List.of("lucene_bm25:" + round(scoreDoc.score))
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
            text.add(new BoostQuery(textQuery(queryText, FIELD_NAME_TEXT, FIELD_TEXT), 2.0f), BooleanClause.Occur.SHOULD);
            text.add(new BoostQuery(exactQuery(FIELD_ID, queryText), 3.0f), BooleanClause.Occur.SHOULD);
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
        addText(document, FIELD_NAME_TEXT, join(doc.name(), doc.displayName(), doc.toolName()));
        addText(document, FIELD_TEXT, join(doc.name(), doc.displayName(), doc.toolName(), String.join(" ", doc.labels())));
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
        addExact(document, "riskLevel", doc.riskLevel());
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

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
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
                           String source) {

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

    public record SearchHit(String id, String kind, float score, List<String> reasons) {
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
