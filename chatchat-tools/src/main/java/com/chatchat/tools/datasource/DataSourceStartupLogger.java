package com.chatchat.tools.datasource;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class DataSourceStartupLogger implements ApplicationRunner {

    private final ObjectProvider<DataSource> dataSourceProvider;

    @Override
    public void run(ApplicationArguments args) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.info("Application datasource: none");
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            log.info(
                "Application database: url={}, database={} {}, driver={} {}, autocommit={}, isolation={}",
                safe(metadata.getURL()),
                safe(metadata.getDatabaseProductName()),
                safe(metadata.getDatabaseProductVersion()),
                safe(metadata.getDriverName()),
                safe(metadata.getDriverVersion()),
                connection.getAutoCommit(),
                isolationName(connection.getTransactionIsolation())
            );
        } catch (Exception ex) {
            log.warn("Failed to read application database metadata: {}", ex.getMessage());
        }

        if (dataSource instanceof HikariDataSource hikari) {
            log.info(
                "Application HikariCP: poolName={}, minimumIdle={}, maximumPoolSize={}, connectionTimeoutMs={}, " +
                    "idleTimeoutMs={}, maxLifetimeMs={}, autoCommit={}",
                hikari.getPoolName(),
                hikari.getMinimumIdle(),
                hikari.getMaximumPoolSize(),
                hikari.getConnectionTimeout(),
                hikari.getIdleTimeout(),
                hikari.getMaxLifetime(),
                hikari.isAutoCommit()
            );
        } else {
            log.info("Application datasource type: {}", dataSource.getClass().getName());
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private String isolationName(int isolation) {
        return switch (isolation) {
            case Connection.TRANSACTION_NONE -> "NONE";
            case Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN(" + isolation + ")";
        };
    }
}
