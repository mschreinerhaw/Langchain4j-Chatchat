package com.chatchat.api.datascience.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** Additive application tables and exact-match built-in manifest upgrade. */
@Component @RequiredArgsConstructor
public class SqlStudioMigration {
    private final DataSource dataSource;
    private final SqlStudioGenerationScopeStore generationScopes;
    @EventListener(ApplicationReadyEvent.class) @Order(110)
    public void migrate() throws java.sql.SQLException {
        String dialect;
        try (var connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            if (product.contains("mysql") || product.contains("mariadb")) dialect = "mysql";
            else if (product.contains("postgres")) dialect = "postgresql";
            else if (product.equals("h2")) dialect = "h2";
            else throw new java.sql.SQLException("SQL Studio schema has no reviewed migration for database product: " + product);
        }
        var populator = new ResourceDatabasePopulator(new ClassPathResource("sql-studio/schema-" + dialect + ".sql"),
            new ClassPathResource("sql-studio/workspace-upgrade.sql"));
        populator.setSqlScriptEncoding("UTF-8"); populator.execute(dataSource);
        generationScopes.backfill();
    }
}
