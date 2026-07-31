package com.chatchat.runtime.news.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "chatchat.runtime.news")
public class NewsRuntimeProperties {

    private boolean enabled = true;
    private int collectorCoreSize = 2;
    private int collectorMaxSize = 4;
    private int collectorQueueCapacity = 20;
    private int maxItemsPerRun = 500;
    private int minimumContentChars = 80;
    private Robots robots = new Robots();
    private Bulk bulk = new Bulk();
    private Attachment attachment = new Attachment();
    private Analysis analysis = new Analysis();
    private CollectLog collectLog = new CollectLog();
    private OpenSearch openSearch = new OpenSearch();
    private WebSearch webSearch = new WebSearch();

    @Data
    public static class WebSearch {
        /** Enables Tencent WSA as an internal retrieval enhancer behind web_search. */
        private boolean enabled = false;
        private String endpoint = "https://wsa.tencentcloudapi.com";
        private String secretId;
        private String secretKey;
        private String region;
        private int mode = 2;
        /** Cnt is a paid-edition parameter in WSA; leave it disabled for compatible defaults. */
        private boolean requestCountEnabled = false;
        private int maxResults = 20;
        private int timeoutMillis = 10_000;
        private int hotspotLookbackDays = 7;
        private int maxSnippetChars = 1_200;
        private Cache cache = new Cache();
    }

    @Data
    public static class Cache {
        /** Reuses highly related Tencent WSA result sets before issuing another paid request. */
        private boolean enabled = true;
        /** Bypasses cache reads and always calls Tencent WSA. Successful responses are still cached. */
        private boolean forceExternal = false;
        private String indexName = "runtime-web-search-cache";
        private int retentionDays = 7;
        private double minimumSimilarity = 0.72D;
        private int maxCandidates = 10;
        private String cleanupCron = "0 45 0 * * *";
        private String zoneId = "Asia/Shanghai";
    }

    @Data
    public static class CollectLog {
        private boolean enabled = true;
        /** Collection log records older than this many days are deleted. */
        private int retentionDays = 7;
        private int cleanupBatchSize = 1_000;
        private int maxBatchesPerRun = 100;
        private String cleanupCron = "0 30 0 * * *";
        private String zoneId = "Asia/Shanghai";
    }

    @Data
    public static class Analysis {
        /** Enables the asynchronous summary, category and tag analysis pipeline. */
        private boolean enabled = true;
        private int batchSize = 20;
        private long pollDelayMillis = 5_000;
        private int summaryMaxChars = 300;
        private int maxTags = 8;
    }

    @Data
    public static class Robots {
        private boolean enabled = true;
        private String userAgent = "ChatChat-NewsCollector";
        private int requestTimeoutMillis = 10_000;
        private int maxFileBytes = 512 * 1024;
        private long cacheTtlMillis = 30 * 60 * 1_000L;
    }

    @Data
    public static class Bulk {
        private int queueCapacity = 5_000;
        private int batchSize = 300;
        private long flushIntervalMillis = 2_000;
        private long offerTimeoutMillis = 3_000;
        private int maxRetries = 2;
        private long firstBackoffMillis = 500;
        private long secondBackoffMillis = 1_500;
    }

    @Data
    public static class Attachment {
        private boolean enabled = true;
        private int workerCount = 2;
        private int queueCapacity = 500;
        private long offerTimeoutMillis = 200;
        private int requestTimeoutMillis = 30_000;
        private long maxFileBytes = 25L * 1024 * 1024;
        private int maxAttachmentsPerArticle = 20;
        private int maxExtractedChars = 2_000_000;
        private int chunkSize = 1_200;
        private int chunkOverlap = 150;
        private int maxChunksPerAttachment = 500;
    }

    @Data
    public static class OpenSearch {
        private boolean enabled = false;
        private String endpoint = "http://localhost:9200";
        private String username;
        private String password;
        private boolean insecureSsl = false;
        private String indexName = "runtime-news";
        /** One physical index is created per calendar day: {indexName}-yyyy.MM.dd. */
        private int retentionDays = 7;
        private String zoneId = "Asia/Shanghai";
        private String retentionCron = "0 15 0 * * *";
        private int connectTimeoutMillis = 5_000;
        private int socketTimeoutMillis = 30_000;
        private Embedding embedding = new Embedding();
    }

    @Data
    public static class Embedding {
        private boolean enabled = false;
        private String endpoint;
        private String apiKey;
        private String model = "text-embedding-v4";
        private int dimension = 1_024;
        private String vectorField = "embedding";
        private int maxInputChars = 8_000;
        private int batchSize = 16;
        private int requestTimeoutMillis = 120_000;
        private int vectorCandidateLimit = 100;
        private int rrfRankConstant = 60;
    }
}
