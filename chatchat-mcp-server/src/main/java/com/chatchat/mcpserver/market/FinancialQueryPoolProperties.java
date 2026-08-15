package com.chatchat.mcpserver.market;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chatchat.mcp.market.query-pool")
public class FinancialQueryPoolProperties {
    private boolean enabled;
    /** LOCAL_H2 isolates collected market data from the control-plane datasource. */
    private String storage = "PRIMARY";
    /** Dedicated file database used by the collector writer and the isolated online read lane. */
    private String localJdbcUrl = "jdbc:h2:file:./data/h2/financial-market;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    private String localUsername = "sa";
    private String localPassword = "";
    /** A single writer serializes schema evolution and idempotent collector upserts. */
    private int writePoolSize = 1;
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

    public boolean isLocalH2() {
        return "LOCAL_H2".equalsIgnoreCase(storage == null ? "" : storage.trim());
    }
}
