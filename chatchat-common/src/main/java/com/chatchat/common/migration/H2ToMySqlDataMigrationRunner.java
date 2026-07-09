package com.chatchat.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "chatchat.datasource-migration", name = "enabled", havingValue = "true")
public class H2ToMySqlDataMigrationRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final DataSourceMigrationProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Class.forName(properties.getSourceDriverClassName());
        try (Connection target = dataSource.getConnection()) {
            String targetUrl = target.getMetaData().getURL();
            if (targetUrl == null || !targetUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:")) {
                log.warn("H2 to MySQL migration is enabled but current datasource is not MySQL: {}", targetUrl);
                return;
            }
        }

        try (Connection source = DriverManager.getConnection(
            properties.getSourceUrl(),
            properties.getSourceUsername(),
            properties.getSourcePassword()
        );
             Connection target = dataSource.getConnection()) {
            source.setReadOnly(true);
            migrate(source, target);
        }
    }

    private void migrate(Connection source, Connection target) throws SQLException {
        List<String> tables = h2Tables(source);
        if (tables.isEmpty()) {
            log.info("H2 to MySQL migration found no source tables");
            return;
        }

        boolean previousAutoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        try (Statement statement = target.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                migrateTable(source, target, table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
            target.commit();
        } catch (Exception ex) {
            target.rollback();
            throw ex;
        } finally {
            try (Statement statement = target.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            } catch (SQLException ex) {
                log.warn("Failed to restore MySQL foreign key checks after migration: {}", ex.getMessage());
            }
            target.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateTable(Connection source, Connection target, String table) throws SQLException {
        if (!targetTableExists(target, table)) {
            log.warn("Skip H2 table {} because target MySQL table does not exist", table);
            return;
        }

        long targetRows = countRows(target, table);
        if (targetRows > 0 && !properties.isReplaceExisting()) {
            log.info("Skip H2 table {} because target already has {} rows", table, targetRows);
            return;
        }
        if (targetRows > 0) {
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate("DELETE FROM " + quote(table));
            }
        }

        List<String> columns = copyableColumns(source, target, table);
        if (columns.isEmpty()) {
            log.info("Skip H2 table {} because it has no copyable columns", table);
            return;
        }
        widenBoundedTextColumns(source, target, table, columns);

        String selectSql = "SELECT " + joinedQuoted(columns) + " FROM " + quote(table);
        String insertSql = "INSERT INTO " + quote(table) + " (" + joinedQuoted(columns) + ") VALUES ("
            + "?,".repeat(columns.size()).replaceFirst(",$", "")
            + ")";
        int batchSize = Math.max(1, properties.getBatchSize());
        long copied = 0;
        try (Statement sourceStatement = source.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rows = sourceStatement.executeQuery(selectSql);
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            ResultSetMetaData metaData = rows.getMetaData();
            int columnCount = metaData.getColumnCount();
            int batchCount = 0;
            while (rows.next()) {
                for (int index = 1; index <= columnCount; index++) {
                    insert.setObject(index, rows.getObject(index));
                }
                insert.addBatch();
                batchCount++;
                copied++;
                if (batchCount >= batchSize) {
                    insert.executeBatch();
                    batchCount = 0;
                }
            }
            if (batchCount > 0) {
                insert.executeBatch();
            }
        }
        log.info("Migrated H2 table {} to MySQL, rows={}", table, copied);
    }

    private List<String> copyableColumns(Connection source, Connection target, String table) throws SQLException {
        List<String> sourceColumns = h2Columns(source, table);
        Set<String> targetColumns = targetColumns(target, table);
        List<String> copyable = new ArrayList<>();
        for (String column : sourceColumns) {
            if (targetColumns.contains(column)) {
                copyable.add(column);
            } else {
                log.debug("Skip H2 column {}.{} because target MySQL column does not exist", table, column);
            }
        }
        return copyable;
    }

    private void widenBoundedTextColumns(Connection source, Connection target, String table, List<String> columns)
        throws SQLException {
        Map<String, ColumnInfo> targetInfo = targetColumnInfo(target, table);
        for (String column : columns) {
            ColumnInfo info = targetInfo.get(column);
            if (info == null || !isBoundedTextColumn(info)) {
                continue;
            }
            long sourceMaxLength = maxSourceCharacterLength(source, table, column);
            if (sourceMaxLength <= info.size()) {
                continue;
            }
            String nullable = info.nullable() ? "" : " NOT NULL";
            try (Statement statement = target.createStatement()) {
                statement.execute("ALTER TABLE " + quote(table) + " MODIFY COLUMN " + quote(column) + " LONGTEXT" + nullable);
            }
            log.info(
                "Widened MySQL column {}.{} from {}({}) to LONGTEXT for H2 migration, sourceMaxLength={}",
                table,
                column,
                info.typeName(),
                info.size(),
                sourceMaxLength
            );
        }
    }

    private List<String> h2Tables(Connection source) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = """
            SELECT TABLE_NAME
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = 'PUBLIC'
              AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """;
        try (Statement statement = source.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                tables.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private List<String> h2Columns(Connection source, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = """
            SELECT COLUMN_NAME
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = 'PUBLIC'
              AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;
        try (PreparedStatement statement = source.prepareStatement(sql)) {
            statement.setString(1, table.toUpperCase(Locale.ROOT));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (columns.isEmpty()) {
            try (PreparedStatement statement = source.prepareStatement(sql)) {
                statement.setString(1, table.toLowerCase(Locale.ROOT));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        columns.add(rows.getString(1).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return columns;
    }

    private boolean targetTableExists(Connection target, String table) throws SQLException {
        DatabaseMetaData metaData = target.getMetaData();
        try (ResultSet rows = metaData.getTables(target.getCatalog(), null, table, new String[] {"TABLE"})) {
            return rows.next();
        }
    }

    private Set<String> targetColumns(Connection target, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        DatabaseMetaData metaData = target.getMetaData();
        try (ResultSet rows = metaData.getColumns(target.getCatalog(), null, table, null)) {
            while (rows.next()) {
                columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private Map<String, ColumnInfo> targetColumnInfo(Connection target, String table) throws SQLException {
        Map<String, ColumnInfo> columns = new HashMap<>();
        DatabaseMetaData metaData = target.getMetaData();
        try (ResultSet rows = metaData.getColumns(target.getCatalog(), null, table, null)) {
            while (rows.next()) {
                String name = rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                columns.put(name, new ColumnInfo(
                    rows.getInt("DATA_TYPE"),
                    rows.getString("TYPE_NAME"),
                    rows.getInt("COLUMN_SIZE"),
                    DatabaseMetaData.columnNoNulls != rows.getInt("NULLABLE")
                ));
            }
        }
        return columns;
    }

    private boolean isBoundedTextColumn(ColumnInfo info) {
        int type = info.jdbcType();
        String typeName = info.typeName() == null ? "" : info.typeName().toLowerCase(Locale.ROOT);
        return (type == Types.CHAR
            || type == Types.VARCHAR
            || type == Types.NCHAR
            || type == Types.NVARCHAR
            || type == Types.LONGVARCHAR
            || typeName.contains("text"))
            && info.size() > 0
            && info.size() < Integer.MAX_VALUE;
    }

    private long maxSourceCharacterLength(Connection source, String table, String column) {
        String sql = "SELECT MAX(CHAR_LENGTH(" + quote(column) + ")) FROM " + quote(table);
        try (Statement statement = source.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (rows.next()) {
                return rows.getLong(1);
            }
        } catch (SQLException ex) {
            log.debug("Failed to inspect H2 column length for {}.{}: {}", table, column, ex.getMessage());
        }
        return 0L;
    }

    private long countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private String joinedQuoted(List<String> values) {
        return values.stream().map(this::quote).reduce((left, right) -> left + "," + right).orElse("");
    }

    private String quote(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private record ColumnInfo(int jdbcType, String typeName, int size, boolean nullable) {
    }
}
