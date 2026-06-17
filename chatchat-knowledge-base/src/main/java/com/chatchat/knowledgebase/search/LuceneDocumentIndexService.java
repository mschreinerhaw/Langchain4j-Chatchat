package com.chatchat.knowledgebase.search;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LuceneDocumentIndexService {

    private static final String FILE_ID = "fileId";
    private static final String FILE_NAME = "fileName";
    private static final String CHUNK_ID = "chunkId";
    private static final String CHUNK_INDEX = "chunkIndex";
    private static final String CHUNK_TEXT = "chunkText";
    private static final String CONTENT = "content";
    private static final String UPLOAD_TIME = "uploadTime";
    private static final String TITLE_TEXT = "title";
    private static final String SECTION = "section";
    private static final String KEYWORDS_TEXT = "keywords";
    private static final String CHUNK_TYPE = "chunkType";
    private static final String POSITION_RATIO = "positionRatio";
    private static final String TITLE = "titleTokens";
    private static final String CONTENT_TOKENS = "contentTokens";
    private static final String SOURCE = "sourceTokens";
    private static final String KEYWORDS = "keywordTokens";
    private static final String TAGS = "tagTokens";
    private static final String COMPANIES = "companyTokens";
    private static final String INDUSTRIES = "industryTokens";

    private final SearchProperties properties;
    private final SearchTokenizer tokenizer;
    private final TextChunker chunker;
    private final KeywordExtractor keywordExtractor;
    private final QueryExpander queryExpander;
    private final ChunkTypeClassifier chunkTypeClassifier;
    private final ChunkReranker chunkReranker;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;

    /**
     * Opens the open.
     */
    @PostConstruct
    public void open() {
        if (!properties.isLuceneEnabled()) {
            return;
        }
        try {
            Path indexPath = Path.of(properties.getLuceneIndexPath()).toAbsolutePath();
            Files.createDirectories(indexPath);
            this.directory = FSDirectory.open(indexPath);
            this.analyzer = new WhitespaceAnalyzer();
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setSimilarity(new BM25Similarity())
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            this.writer = new IndexWriter(directory, config);
            log.info("Lucene search index opened at {}", indexPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to open Lucene search index", ex);
        }
    }

    /**
     * Performs the index latest operation.
     *
     * @param document the document value
     */
    public synchronized void indexLatest(SearchDocument document) {
        if (!isAvailable() || document == null || document.getDocId() == null || document.getDocId().isBlank()) {
            return;
        }
        try {
            writer.deleteDocuments(new Term(FILE_ID, document.getDocId()));
            if (Boolean.FALSE.equals(document.getLatestVersion())) {
                writer.commit();
                return;
            }
            List<TextChunker.TextChunk> chunks = chunks(document.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                writer.addDocument(toLuceneDocument(document, chunks.get(i), i, chunks.size()));
            }
            writer.commit();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to index document into Lucene: " + document.getDocId(), ex);
        }
    }

    /**
     * Deletes the document.
     *
     * @param docId the doc id value
     */
    public synchronized void deleteDocument(String docId) {
        if (!isAvailable() || docId == null || docId.isBlank()) {
            return;
        }
        try {
            writer.deleteDocuments(new Term(FILE_ID, docId));
            writer.commit();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete document from Lucene: " + docId, ex);
        }
    }

    /**
     * Performs the rebuild latest operation.
     *
     * @param documents the documents value
     */
    public synchronized void rebuildLatest(List<SearchDocument> documents) {
        if (!isAvailable()) {
            return;
        }
        try {
            writer.deleteAll();
            if (documents != null) {
                for (SearchDocument document : documents) {
                    if (document != null && !Boolean.FALSE.equals(document.getLatestVersion())) {
                        List<TextChunker.TextChunk> chunks = chunks(document.getContent());
                        for (int i = 0; i < chunks.size(); i++) {
                            writer.addDocument(toLuceneDocument(document, chunks.get(i), i, chunks.size()));
                        }
                    }
                }
            }
            writer.commit();
            log.info("Lucene search index rebuilt with {} latest documents", documents == null ? 0 : documents.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to rebuild Lucene search index", ex);
        }
    }

    /**
     * Searches the search.
     *
     * @param keyword the keyword value
     * @param maxHits the max hits value
     * @return the operation result
     */
    public synchronized List<LuceneSearchHit> search(String keyword, int maxHits) {
        if (!isAvailable() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        QueryIntent intent = queryExpander.classifyIntent(keyword);
        String intentName = queryExpander.classifyIntentName(keyword);
        List<String> terms = queryExpander.expandTokens(tokenizer.searchTokens(keyword), intentName, keyword);
        if (terms.isEmpty()) {
            return List.of();
        }
        try {
            writer.commit();
            BooleanQuery query = buildQuery(terms);
            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                TopDocs topDocs = searcher.search(query, Math.max(1, maxHits));
                List<LuceneSearchHit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document hit = searcher.storedFields().document(scoreDoc.doc);
                    String docId = hit.get(FILE_ID);
                    if (docId == null || docId.isBlank()) {
                        continue;
                    }
                    LuceneSearchHit current = new LuceneSearchHit(
                        docId,
                        hit.get(FILE_NAME),
                        hit.get(SECTION),
                        hit.get(CHUNK_TYPE),
                        hit.get(CHUNK_ID),
                        intValue(hit.get(CHUNK_INDEX)),
                        firstPresent(hit.get(CONTENT), hit.get(CHUNK_TEXT)),
                        floatValue(hit.get(POSITION_RATIO), 1.0F),
                        chunkReranker.score(scoreDoc.score, hit, keyword, terms, intent)
                    );
                    hits.add(current);
                }
                return hits;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to search Lucene index", ex);
        }
    }

    /**
     * Returns whether is available.
     *
     * @return whether the condition is satisfied
     */
    public boolean isAvailable() {
        return properties.isLuceneEnabled() && writer != null;
    }

    /**
     * Closes the close.
     */
    @PreDestroy
    public void close() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ex) {
            log.warn("Failed to close Lucene writer: {}", ex.getMessage());
        }
        if (analyzer != null) {
            analyzer.close();
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException ex) {
            log.warn("Failed to close Lucene directory: {}", ex.getMessage());
        }
    }

    /**
     * Converts the value to lucene document.
     *
     * @param document the document value
     * @param chunkText the chunk text value
     * @param chunkIndex the chunk index value
     * @return the converted lucene document
     */
    private Document toLuceneDocument(SearchDocument document,
                                      TextChunker.TextChunk chunk,
                                      int chunkIndex,
                                      int chunkCount) {
        Document luceneDocument = new Document();
        String chunkId = document.getDocId() + "_" + chunkIndex;
        String chunkText = chunk.content();
        String section = nullToEmpty(chunk.section());
        String keywordText = String.join(" ", keywordExtractor.mergeKeywords(document.getKeywords(), chunkText));
        String chunkType = chunkTypeClassifier.classify(document, chunk);
        String positionRatio = String.valueOf(positionRatio(chunkIndex, chunkCount));
        luceneDocument.add(new StringField(FILE_ID, document.getDocId(), Field.Store.YES));
        luceneDocument.add(new StringField(FILE_NAME, nullToEmpty(document.getFileName()), Field.Store.YES));
        luceneDocument.add(new StringField(CHUNK_ID, chunkId, Field.Store.YES));
        luceneDocument.add(new StringField(CHUNK_TYPE, chunkType, Field.Store.YES));
        luceneDocument.add(new IntPoint(CHUNK_INDEX, chunkIndex));
        luceneDocument.add(new StoredField(CHUNK_INDEX, String.valueOf(chunkIndex)));
        luceneDocument.add(new StoredField(CHUNK_TEXT, chunkText));
        luceneDocument.add(new TextField(CONTENT, chunkText, Field.Store.YES));
        luceneDocument.add(new StoredField(UPLOAD_TIME, document.getUploadedAt() == null ? 0L : document.getUploadedAt()));
        luceneDocument.add(new StoredField(POSITION_RATIO, positionRatio));
        luceneDocument.add(new TextField(TITLE_TEXT, nullToEmpty(document.getTitle()), Field.Store.YES));
        luceneDocument.add(new TextField(SECTION, section, Field.Store.YES));
        luceneDocument.add(new TextField(KEYWORDS_TEXT, keywordText, Field.Store.YES));
        addTokenField(luceneDocument, TITLE, document.getTitle());
        addTokenField(luceneDocument, TITLE, document.getFileName());
        addTokenField(luceneDocument, SECTION, section);
        addTokenField(luceneDocument, KEYWORDS_TEXT, keywordText);
        addTokenField(luceneDocument, CONTENT_TOKENS, chunkText);
        addTokenField(luceneDocument, SOURCE, document.getSource());
        addTokenField(luceneDocument, KEYWORDS, document.getKeywords());
        addTokenField(luceneDocument, TAGS, document.getTags());
        addTokenField(luceneDocument, COMPANIES, document.getCompanies());
        addTokenField(luceneDocument, INDUSTRIES, document.getIndustries());
        return luceneDocument;
    }

    /**
     * Builds the query.
     *
     * @param terms the terms value
     * @return the built query
     */
    private BooleanQuery buildQuery(List<String> terms) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder termQuery = new BooleanQuery.Builder();
            addTermQuery(termQuery, TITLE, term, 5.0F);
            addTermQuery(termQuery, TITLE_TEXT, term, 5.0F);
            addTermQuery(termQuery, SECTION, term, 4.6F);
            addTermQuery(termQuery, KEYWORDS, term, 4.2F);
            addTermQuery(termQuery, KEYWORDS_TEXT, term, 4.2F);
            addTermQuery(termQuery, TAGS, term, 4.5F);
            addTermQuery(termQuery, COMPANIES, term, 3.5F);
            addTermQuery(termQuery, INDUSTRIES, term, 3.5F);
            addTermQuery(termQuery, CONTENT_TOKENS, term, 1.2F);
            addTermQuery(termQuery, SOURCE, term, 0.7F);
            query.add(termQuery.build(), BooleanClause.Occur.SHOULD);
        }
        query.setMinimumNumberShouldMatch(minimumShouldMatch(terms.size()));
        return query.build();
    }

    /**
     * Performs the minimum should match operation.
     *
     * @param termCount the term count value
     * @return the operation result
     */
    private int minimumShouldMatch(int termCount) {
        return 1;
    }

    /**
     * Adds the term query.
     *
     * @param query the query value
     * @param field the field value
     * @param term the term value
     * @param boost the boost value
     */
    private void addTermQuery(BooleanQuery.Builder query, String field, String term, float boost) {
        query.add(new BoostQuery(new TermQuery(new Term(field, term)), boost), BooleanClause.Occur.SHOULD);
    }

    /**
     * Adds the token field.
     *
     * @param document the document value
     * @param field the field value
     * @param value the value value
     */
    private void addTokenField(Document document, String field, String value) {
        String tokenText = tokenText(value);
        if (!tokenText.isBlank()) {
            document.add(new TextField(field, tokenText, Field.Store.NO));
        }
    }

    /**
     * Adds the token field.
     *
     * @param document the document value
     * @param field the field value
     * @param values the values value
     */
    private void addTokenField(Document document, String field, List<String> values) {
        String tokenText = tokenText(values);
        if (!tokenText.isBlank()) {
            document.add(new TextField(field, tokenText, Field.Store.NO));
        }
    }

    /**
     * Converts the value to ken text.
     *
     * @param value the value value
     * @return the converted ken text
     */
    private String tokenText(String value) {
        return String.join(" ", tokenizer.tokenizeOccurrences(value));
    }

    /**
     * Converts the value to ken text.
     *
     * @param values the values value
     * @return the converted ken text
     */
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

    /**
     * Performs the chunks operation.
     *
     * @param content the content value
     * @return the operation result
     */
    private List<TextChunker.TextChunk> chunks(String content) {
        List<TextChunker.TextChunk> chunks = chunker.splitChunks(content, properties.getChunkSize(), properties.getChunkOverlap());
        return chunks.isEmpty() ? List.of(new TextChunker.TextChunk("", "")) : chunks;
    }

    /**
     * Performs the int value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private int intValue(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private float floatValue(String value, float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String firstPresent(String first, String second) {
        return first == null ? second : first;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private float positionRatio(int chunkIndex, int chunkCount) {
        if (chunkCount <= 1) {
            return 0.0F;
        }
        return (float) chunkIndex / (float) (chunkCount - 1);
    }
}
