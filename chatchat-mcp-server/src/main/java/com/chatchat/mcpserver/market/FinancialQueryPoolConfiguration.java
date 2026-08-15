package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.storage.FinancialReadOperations;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/** Dedicated read pool: slow financial analytics cannot consume control-plane/JPA connections. */
@Configuration
@EnableConfigurationProperties(FinancialQueryPoolProperties.class)
public class FinancialQueryPoolConfiguration {

    @Bean(name = "financialReadOperations", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "chatchat.mcp.market.query-pool", name = "enabled", havingValue = "true")
    public IsolatedFinancialReadOperations financialReadOperations(DataSourceProperties primary,
                                                                   FinancialQueryPoolProperties properties) {
        HikariConfig config = hikariConfig(primary, properties);
        int queryTimeoutSeconds = Math.max(1, Math.min(20, properties.getQueryTimeoutSeconds()));
        return new IsolatedFinancialReadOperations(config, queryTimeoutSeconds);
    }

    HikariConfig hikariConfig(DataSourceProperties primary, FinancialQueryPoolProperties properties) {
        int queryTimeoutSeconds = Math.max(1, Math.min(20, properties.getQueryTimeoutSeconds()));
        int queryTimeoutMs = Math.multiplyExact(queryTimeoutSeconds, 1_000);
        HikariConfig config = new HikariConfig();
        config.setPoolName("FinancialQueryPool");
        config.setJdbcUrl(primary.determineUrl());
        config.setUsername(primary.determineUsername());
        config.setPassword(primary.determinePassword());
        config.setDriverClassName(primary.determineDriverClassName());
        config.setMaximumPoolSize(Math.max(1, Math.min(16, properties.getMaximumPoolSize())));
        config.setMinimumIdle(Math.max(0, Math.min(config.getMaximumPoolSize(), properties.getMinimumIdle())));
        config.setConnectionTimeout(Math.max(250L, properties.getConnectionTimeoutMs()));
        config.setValidationTimeout(Math.max(250L, properties.getValidationTimeoutMs()));
        config.setIdleTimeout(Math.max(10_000L, properties.getIdleTimeoutMs()));
        config.setMaxLifetime(Math.max(30_000L, properties.getMaxLifetimeMs()));
        if (properties.getLeakDetectionMs() >= 2_000L) {
            config.setLeakDetectionThreshold(properties.getLeakDetectionMs());
        }
        if (isMySql(primary)) {
            int networkTimeoutMs = properties.getNetworkTimeoutMs() > 0
                ? properties.getNetworkTimeoutMs() : queryTimeoutMs + 2_000;
            int serverExecutionTimeoutMs = properties.getServerExecutionTimeoutMs() > 0
                ? properties.getServerExecutionTimeoutMs() : queryTimeoutMs;
            // Statement.setQueryTimeout alone is cooperative in Connector/J and may leave
            // the server query running after Future.cancel(true). These two independent
            // deadlines bound both server execution and a non-responsive network read.
            config.addDataSourceProperty("enableQueryTimeouts", true);
            config.addDataSourceProperty("socketTimeout", Math.max(queryTimeoutMs, networkTimeoutMs));
            config.setConnectionInitSql("SET SESSION MAX_EXECUTION_TIME="
                + Math.max(1_000, serverExecutionTimeoutMs));
        }
        config.setReadOnly(true);
        return config;
    }

    private boolean isMySql(DataSourceProperties primary) {
        String url = primary.determineUrl();
        String driver = primary.determineDriverClassName();
        return (url != null && url.regionMatches(true, 0, "jdbc:mysql:", 0, 11))
            || (driver != null && driver.toLowerCase(java.util.Locale.ROOT).contains("mysql"));
    }

    public static final class IsolatedFinancialReadOperations implements FinancialReadOperations, AutoCloseable {
        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;

        IsolatedFinancialReadOperations(HikariConfig config, int queryTimeoutSeconds) {
            this.dataSource = new HikariDataSource(config);
            this.jdbc = new JdbcTemplate(dataSource);
            this.jdbc.setQueryTimeout(Math.max(1, Math.min(20, queryTimeoutSeconds)));
        }

        @Override
        public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... arguments) {
            return jdbc.queryForList(sql, arguments);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql,
                                                      Map<String, Object> parameters,
                                                      int maxRows,
                                                      int timeoutSeconds) {
            JdbcTemplate bounded = new JdbcTemplate(dataSource);
            bounded.setMaxRows(Math.max(1, Math.min(500, maxRows)));
            bounded.setQueryTimeout(Math.max(1, Math.min(20, timeoutSeconds)));
            return new NamedParameterJdbcTemplate(bounded).queryForList(sql, parameters);
        }

        @Override public void close() { dataSource.close(); }
    }
}
