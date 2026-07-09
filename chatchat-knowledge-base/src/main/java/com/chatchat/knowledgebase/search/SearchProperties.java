package com.chatchat.knowledgebase.search;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.search")
public class SearchProperties {

    private String storePath = "./data/search-rocksdb";
    private String filePath = "./data/search-files";
    private boolean createIfMissing = true;
    private long maxUploadBytes = 5 * 1024 * 1024;
    private int defaultLimit = 20;
    private int maxLimit = 100;
    private int summaryLength = 180;
    private boolean rebuildOnStartup = false;
    private String engine = "lucene";
    private boolean luceneEnabled = true;
    private String luceneIndexPath = "./data/search-lucene";
    private int luceneMaxHits = 500;
    private int luceneChunksPerDocument = 3;
    private int luceneMaxQueryTerms = 80;
    private int luceneMaxNegativeQueryTerms = 24;
    private int fallbackCandidateLimit = 500;
    private int fallbackEmptyResultLimit = 200;
    private int fallbackExceptionLimit = 100;
    private boolean realtimeDocumentCountEnabled = false;
    private boolean tenantIsolationEnabled = false;
    private QueryBudget queryBudget = new QueryBudget();
    private RetrievalControl retrievalControl = new RetrievalControl();
    private HybridRetrieval hybridRetrieval = new HybridRetrieval();
    private Ocr ocr = new Ocr();
    private OpenSearch openSearch = new OpenSearch();
    private boolean lucenePrfEnabled = true;
    private int lucenePrfTopN = 20;
    private int lucenePrfMaxTerms = 8;
    private int lucenePrfMinTermLength = 2;
    private boolean luceneMmrEnabled = true;
    private int luceneMmrCandidateLimit = 120;
    private float luceneMmrLambda = 0.72F;
    private boolean luceneRocchioEnabled = false;
    private int luceneRocchioMaxTerms = 6;
    private int luceneRocchioFeedbackLimit = 80;
    private float luceneTitleBoost = 5.0F;
    private float luceneSectionBoost = 4.6F;
    private float luceneKeywordBoost = 4.2F;
    private float luceneTagBoost = 4.5F;
    private float luceneCompanyBoost = 3.5F;
    private float luceneIndustryBoost = 3.5F;
    private float luceneContentBoost = 1.2F;
    private float luceneSourceBoost = 0.7F;
    private int chunkSize = 800;
    private int chunkOverlap = 120;

    @Getter
    @Setter
    public static class QueryBudget {
        private int maxDocScan = 1000;
        private int maxRocksdbIter = 2000;
    }

    @Getter
    @Setter
    public static class RetrievalControl {
        private boolean enabled = true;
        private boolean queryValidationEnabled = true;
        private boolean qualityScoringEnabled = true;
        private int maxSearchCalls = 1;
        private int maxAttempts = 1;
        private long latencyMs = 100L;
        private double minQualityScore = 20.0D;
        private int minSpecificTokens = 2;
    }

    @Getter
    @Setter
    public static class HybridRetrieval {
        private boolean enabled = true;
        private int globalDocumentLimit = 8;
        private int globalChunkLimit = 50;
        private int candidateDocumentLimit = 5;
        private int chunksPerDocument = 3;
    }

    @Getter
    @Setter
    public static class Ocr {
        private boolean enabled = true;
        private String language = "eng+chi_sim";
        private int timeoutSeconds = 60;
        private boolean preserveInterwordSpacing = true;
        private boolean imagePreprocessingEnabled = true;
        private String pdfStrategy = "auto";
        private String marker = "# OCR_TEXT";
        private float scorePenalty = 0.7F;
    }

    @Getter
    @Setter
    public static class OpenSearch {
        private boolean enabled = true;
        private String url = "";
        private String username = "";
        private String password = "";
        private String indexName = "chatchat_documents";
        private boolean insecureSsl = false;
        private int connectTimeoutMs = 5000;
        private int requestTimeoutMs = 30000;
        private int bulkBatchSize = 200;
        private Embedding embedding = new Embedding();

        @Getter
        @Setter
        public static class Embedding {
            private boolean enabled = false;
            private String endpoint = "";
            private String apiKey = "";
            private String model = "";
            private int dimension = 1024;
            private String vectorField = "contentVector";
            private int maxInputChars = 6000;
            private int vectorCandidateLimit = 50;
            private float bm25Weight = 0.45F;
            private float vectorWeight = 0.35F;
            private float titleWeight = 0.10F;
            private float freshnessWeight = 0.05F;
            private float authorityWeight = 0.05F;
        }
    }

    public boolean isOpenSearchEngine() {
        return "opensearch".equalsIgnoreCase(engine) || "open-search".equalsIgnoreCase(engine);
    }

    public boolean isLuceneEngine() {
        return engine == null || engine.isBlank() || "lucene".equalsIgnoreCase(engine);
    }
}
