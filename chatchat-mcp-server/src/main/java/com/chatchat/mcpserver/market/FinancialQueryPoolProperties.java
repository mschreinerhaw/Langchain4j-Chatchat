package com.chatchat.mcpserver.market;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chatchat.mcp.market.query-pool")
public class FinancialQueryPoolProperties {
    private boolean enabled;
    private int maximumPoolSize = 4;
    private int minimumIdle = 0;
    private long connectionTimeoutMs = 2_000;
    private long validationTimeoutMs = 1_000;
    private long idleTimeoutMs = 120_000;
    private long maxLifetimeMs = 600_000;
    private long leakDetectionMs = 30_000;
    private int queryTimeoutSeconds = 15;
    /** Hard driver read deadline; zero derives a small grace period from queryTimeoutSeconds. */
    private int networkTimeoutMs;
    /** MySQL server-side SELECT deadline; zero derives it from queryTimeoutSeconds. */
    private int serverExecutionTimeoutMs;
}
