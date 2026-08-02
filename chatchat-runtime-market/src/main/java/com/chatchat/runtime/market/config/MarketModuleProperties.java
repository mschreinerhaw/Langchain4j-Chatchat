package com.chatchat.runtime.market.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-connection behavior settings; datasource, OpenSearch and credentials are owned by MCP Server. */
@Data
@ConfigurationProperties(prefix = "chatchat.mcp.market")
public class MarketModuleProperties {
    private boolean enabled = true;
    private String catalogIndexName = "financial-data-asset";
    private int defaultQueryLimit = 50;
    private int maxQueryLimit = 200;
    /** JDBC statement timeout; must remain below the outer MCP tool deadline. */
    private int queryTimeoutSeconds = 20;
    /** Database bulkhead: analytical reads can never consume the entire application pool. */
    private int maxConcurrentQueries = 4;
    /** Fail fast rather than queue Agent requests while the financial read lane is saturated. */
    private long queryQueueTimeoutMs = 100;
    private int partitionCount = 32;
    private QueryCache queryCache = new QueryCache();
    private Retention retention = new Retention();

    @Data
    public static class QueryCache {
        /** Financial reads are cached by default to protect the bounded JDBC lane. */
        private boolean enabled = true;
        /** ROCKSDB is local and always preferred unless an operator selects REDIS. */
        private String storage = "ROCKSDB";
        /** Thirty-minute freshness window for governed financial observations. */
        private long ttlSeconds = 30 * 60L;
        /** A selected but unavailable Redis must not take the financial query path down. */
        private boolean fallbackToRocksDb = true;
        private int maxEntryKb = 2048;
        /** Briefly shares an in-flight result with queued callers, including oversized non-cacheable results. */
        private long singleFlightGraceMs = 500;
    }

    @Data
    public static class Retention {
        /** Keeps daily observations available for short-range analysis. */
        private boolean enabled = true;
        private int hotDays = 7;
        /** Weekly snapshots are small enough to keep as a multi-year analysis baseline. */
        private int weeklyArchiveDays = 1825;
        private String cron = "0 30 2 * * SUN";
        private String zoneId = "Asia/Shanghai";
    }
}
