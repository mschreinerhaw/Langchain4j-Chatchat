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
    private int partitionCount = 32;
    private Retention retention = new Retention();

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
