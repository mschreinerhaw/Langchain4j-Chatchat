package com.chatchat.runtime.market.storage;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Dedicated collector write resources kept outside Spring Boot's primary
 * DataSource/JdbcTemplate bean types so control-plane JPA remains on MySQL.
 */
public final class FinancialWriteStorage implements AutoCloseable {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public FinancialWriteStorage(DataSource dataSource, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(Math.max(1, Math.min(20, queryTimeoutSeconds)));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        if (!(dataSource instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to close financial write storage", ex);
        }
    }
}
