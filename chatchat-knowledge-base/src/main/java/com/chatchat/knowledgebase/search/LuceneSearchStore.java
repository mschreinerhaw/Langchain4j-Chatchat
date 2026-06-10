package com.chatchat.knowledgebase.search;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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
class LuceneSearchStore {

    private static final String DOC_ID = "docId";
    private static final String CHUNK_ID = "chunkId";
    private static final String CHUNK_INDEX = "chunkIndex";
    private static final String CHUNK_TEXT = "chunkText";
    private static final String TITLE = "titleTokens";
    private static final String CONTENT = "contentTokens";
    private static final String SOURCE = "sourceTokens";
    private static final String KEYWORDS = "keywordTokens";
    private static final String TAGS = "tagTokens";
    private static final String COMPANIES = "companyTokens";
    private static final String INDUSTRIES = "industryTokens";

    private final SearchProperties properties;
    private final SearchTokenizer tokenizer;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter writer;

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

    public synchronized void indexLatest(SearchDocument document) {
        if (!isAvailable() || document == null || document.getDocId() == null || document.getDocId().isBlank()) {
            return;
        }
        try {
            writer.deleteDocuments(new Term(DOC_ID, document.getDocId()));
            if (Boolean.FALSE.equals(document.getLatestVersion())) {
                writer.commit();
                return;
            }
            List<String> chunks = chunks(document.getContent());
            for (int i = 0; i < chunks.size(); i++) {
                writer.addDocument(toLuceneDocument(document, chunks.get(i), i));
            }
            writer.commit();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to index document into Lucene: " + document.getDocId(), ex);
        }
    }

    public synchronized void deleteDocument(String docId) {
        if (!isAvailable() || docId == null || docId.isBlank()) {
            return;
        }
        try {
            writer.deleteDocuments(new Term(DOC_ID, docId));
            writer.commit();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete document from Lucene: " + docId, ex);
        }
    }

    public synchronized void rebuildLatest(List<SearchDocument> documents) {
        if (!isAvailable()) {
            return;
        }
        try {
            writer.deleteAll();
            if (documents != null) {
                for (SearchDocument document : documents) {
                    if (document != null && !Boolean.FALSE.equals(document.getLatestVersion())) {
                        List<String> chunks = chunks(document.getContent());
                        for (int i = 0; i < chunks.size(); i++) {
                            writer.addDocument(toLuceneDocument(document, chunks.get(i), i));
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

    public synchronized List<LuceneSearchHit> search(String keyword, int maxHits) {
        if (!isAvailable() || keyword == null || keyword.isBlank()) {
            return List.of();
        }
        List<String> terms = tokenizer.searchTokens(keyword);
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
                    String docId = hit.get(DOC_ID);
                    if (docId == null || docId.isBlank()) {
                        continue;
                    }
                    LuceneSearchHit current = new LuceneSearchHit(
                        docId,
                        hit.get(CHUNK_ID),
                        intValue(hit.get(CHUNK_INDEX)),
                        hit.get(CHUNK_TEXT),
                        scoreDoc.score
                    );
                    hits.add(current);
                }
                return hits;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to search Lucene index", ex);
        }
    }

    public boolean isAvailable() {
        return properties.isLuceneEnabled() && writer != null;
    }

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

    private Document toLuceneDocument(SearchDocument document, String chunkText, int chunkIndex) {
        Document luceneDocument = new Document();
        String chunkId = document.getDocId() + "#" + chunkIndex;
        luceneDocument.add(new StringField(DOC_ID, document.getDocId(), Field.Store.YES));
        luceneDocument.add(new StringField(CHUNK_ID, chunkId, Field.Store.YES));
        luceneDocument.add(new StoredField(CHUNK_INDEX, String.valueOf(chunkIndex)));
        luceneDocument.add(new StoredField(CHUNK_TEXT, chunkText));
        addTokenField(luceneDocument, TITLE, document.getTitle());
        addTokenField(luceneDocument, CONTENT, chunkText);
        addTokenField(luceneDocument, SOURCE, document.getSource());
        addTokenField(luceneDocument, KEYWORDS, document.getKeywords());
        addTokenField(luceneDocument, TAGS, document.getTags());
        addTokenField(luceneDocument, COMPANIES, document.getCompanies());
        addTokenField(luceneDocument, INDUSTRIES, document.getIndustries());
        return luceneDocument;
    }

    private BooleanQuery buildQuery(List<String> terms) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder termQuery = new BooleanQuery.Builder();
            addTermQuery(termQuery, TITLE, term, 5.0F);
            addTermQuery(termQuery, KEYWORDS, term, 4.2F);
            addTermQuery(termQuery, TAGS, term, 4.5F);
            addTermQuery(termQuery, COMPANIES, term, 3.5F);
            addTermQuery(termQuery, INDUSTRIES, term, 3.5F);
            addTermQuery(termQuery, CONTENT, term, 1.2F);
            addTermQuery(termQuery, SOURCE, term, 0.7F);
            query.add(termQuery.build(), BooleanClause.Occur.SHOULD);
        }
        query.setMinimumNumberShouldMatch(minimumShouldMatch(terms.size()));
        return query.build();
    }

    private int minimumShouldMatch(int termCount) {
        if (termCount <= 1) {
            return 1;
        }
        if (termCount == 2) {
            return 1;
        }
        if (termCount <= 5) {
            return 2;
        }
        return Math.max(2, (int) Math.ceil(termCount * 0.35D));
    }

    private void addTermQuery(BooleanQuery.Builder query, String field, String term, float boost) {
        query.add(new BoostQuery(new TermQuery(new Term(field, term)), boost), BooleanClause.Occur.SHOULD);
    }

    private void addTokenField(Document document, String field, String value) {
        String tokenText = tokenText(value);
        if (!tokenText.isBlank()) {
            document.add(new TextField(field, tokenText, Field.Store.NO));
        }
    }

    private void addTokenField(Document document, String field, List<String> values) {
        String tokenText = tokenText(values);
        if (!tokenText.isBlank()) {
            document.add(new TextField(field, tokenText, Field.Store.NO));
        }
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

    private List<String> chunks(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return List.of("");
        }
        int chunkSize = Math.max(200, properties.getChunkSize());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlap(), chunkSize / 2));
        List<String> blocks = paragraphBlocks(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (block.length() > chunkSize) {
                flushChunk(chunks, current);
                chunks.addAll(splitLongBlock(block, chunkSize, overlap));
                continue;
            }
            if (current.length() == 0) {
                current.append(block);
                continue;
            }
            if (current.length() + 2 + block.length() <= chunkSize) {
                current.append("\n\n").append(block);
            } else {
                flushChunk(chunks, current);
                current.append(block);
            }
        }
        flushChunk(chunks, current);
        return chunks.isEmpty() ? List.of(normalized.replaceAll("\\s+", " ").trim()) : chunks;
    }

    private List<String> paragraphBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = "";
        for (String rawLine : content.split("\\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                flushBlock(blocks, current, currentHeading);
                continue;
            }
            if (isHeading(line)) {
                flushBlock(blocks, current, currentHeading);
                currentHeading = line.replaceAll("\\s+", " ");
                continue;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(line.replaceAll("\\s+", " "));
        }
        flushBlock(blocks, current, currentHeading);
        if (blocks.isEmpty()) {
            blocks.add(content.replaceAll("\\s+", " ").trim());
        }
        return blocks;
    }

    private boolean isHeading(String line) {
        return line.startsWith("#")
            || line.matches("^\\d+(\\.\\d+)*[\\u3001.\\)]\\s+.+")
            || line.matches("^[\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u4e03\\u516b\\u4e5d\\u5341]+[\\u3001.]\\s*.+");
    }

    private void flushBlock(List<String> blocks, StringBuilder current, String heading) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString().trim();
        if (!text.isBlank()) {
            blocks.add((heading == null || heading.isBlank() ? "" : heading + "\n") + text);
        }
        current.setLength(0);
    }

    private void flushChunk(List<String> chunks, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString().trim();
        if (!text.isBlank()) {
            chunks.add(text);
        }
        current.setLength(0);
    }

    private List<String> splitLongBlock(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String heading = leadingHeading(text);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String chunk = text.substring(start, end).trim();
            if (!heading.isBlank() && !chunk.startsWith(heading)) {
                chunk = heading + "\n" + chunk;
            }
            chunks.add(chunk);
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private String leadingHeading(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        if (newline <= 0) {
            return "";
        }
        String firstLine = text.substring(0, newline).trim();
        return isHeading(firstLine) ? firstLine : "";
    }

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
}
