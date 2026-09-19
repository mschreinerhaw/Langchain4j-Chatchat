package com.chatchat.api.contract;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialSeedSqlContractTest {

    @Test
    void apiSeedProvidesTwentyFinancialSkillsAndAgentsButPublishesOnlyFive() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:financial_seed_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
            run(connection, schema("h2", "chatchat-api.sql"));
            run(connection, schema("h2", "chatchat-api-securities-seed.sql"));

            assertThat(count(connection, "select count(*) from ds_domain_skill where builtin = true")).isEqualTo(20);
            assertThat(count(connection, "select count(*) from ds_domain_skill where status = 'PUBLISHED'")).isEqualTo(5);
            assertThat(count(connection, "select count(*) from skill_config where builtin = true")).isEqualTo(20);
            assertThat(count(connection, "select count(*) from skill_config where market_status = 'published'")).isEqualTo(5);
            assertThat(count(connection, "select count(*) from kb_document_business_category where builtin = true"))
                .isEqualTo(20);
            assertThat(count(connection, "select count(*) from kb_document_business_category where code = 'periodic_report'"))
                .isEqualTo(1);
        }
    }

    @Test
    void mcpSeedProvidesSecuritiesBusinessClassifications() throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:h2:mem:financial_seed_mcp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
            run(connection, schema("h2", "chatchat-mcp-server.sql"));
            run(connection, schema("h2", "chatchat-mcp-securities-seed.sql"));

            assertThat(count(connection, "select count(*) from mcp_business_category where code <> 'default'"))
                .isEqualTo(20);
            assertThat(count(connection, "select count(*) from mcp_business_category where code = 'securities_market'"))
                .isEqualTo(1);
        }
    }

    private void run(Connection connection, Path script) throws Exception {
        try (Reader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Path schema(String database, String file) {
        Path moduleRelative = Path.of("..", "database", "init", database, file).normalize();
        return Files.exists(moduleRelative)
            ? moduleRelative
            : Path.of("database", "init", database, file).normalize();
    }
}
