package com.chatchat.mcpserver.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

@Slf4j
@Component
public class MetadataGovernanceSchemaMigrator {

    private static final String TABLE_NAME = "mcp_metadata_governance_policy";
    private static final String COLUMN_NAME = "policy_json";

    private final DataSource dataSource;

    public MetadataGovernanceSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Widens governance JSON storage created by earlier releases before bootstrap data is written.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE + 70)
    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = normalized(metadata.getDatabaseProductName());
            if (!product.contains("mysql") && !product.contains("mariadb")) {
                return;
            }
            String typeName = columnType(metadata, connection.getCatalog());
            if (typeName == null || "longtext".equals(typeName)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "alter table " + TABLE_NAME
                        + " modify column " + COLUMN_NAME + " longtext not null"
                );
            }
            log.info("Expanded {}.{} from {} to LONGTEXT", TABLE_NAME, COLUMN_NAME, typeName);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to migrate metadata governance policy storage", ex);
        }
    }

    private String columnType(DatabaseMetaData metadata, String catalog) throws Exception {
        try (ResultSet columns = metadata.getColumns(catalog, null, TABLE_NAME, COLUMN_NAME)) {
            if (columns.next()) {
                return normalized(columns.getString("TYPE_NAME"));
            }
        }
        return null;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
