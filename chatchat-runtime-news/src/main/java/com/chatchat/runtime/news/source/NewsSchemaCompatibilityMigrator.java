package com.chatchat.runtime.news.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Small compatibility migrations that must run before MCP preset synchronization reaches News Runtime. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NewsSchemaCompatibilityMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(NewsSchemaCompatibilityMigrator.class);
    private final DataSource dataSource;

    public NewsSchemaCompatibilityMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String database;
        try (var connection = dataSource.getConnection()) {
            database = connection.getMetaData().getDatabaseProductName();
        }
        if (!"H2".equalsIgnoreCase(database)) return;
        // Hibernate 6 used a native H2 ENUM for @Enumerated. Convert it once so future source types are additive.
        new JdbcTemplate(dataSource).execute("alter table news_source alter column source_type varchar(32)");
        log.info("news_schema_compatibility_applied database=H2 migration=source_type_enum_to_varchar");
    }
}
