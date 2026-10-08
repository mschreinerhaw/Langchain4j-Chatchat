package com.chatchat.chat.interaction.service;

import com.chatchat.agents.runtime.plan.RuntimeSemanticPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSemanticPolicyMigrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void h2MigrationPublishesExecutablePolicyAndOtherDialectsCarrySameSeed() throws Exception {
        String h2 = migration("h2");
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:runtime_semantic_policy_migration")) {
            try (var statement = connection.createStatement()) {
                for (String sql : h2.split(";\\s*")) {
                    if (!sql.isBlank()) statement.execute(sql);
                }
            }
            try (var result = connection.createStatement().executeQuery(
                "select policy_json from agent_runtime_semantic_policy where policy_key='default'")) {
                assertThat(result.next()).isTrue();
                Map<String, Object> values = mapper.readValue(result.getString(1), new TypeReference<>() { });
                RuntimeSemanticPolicy policy = RuntimeSemanticPolicy.from(values);
                assertThat(policy.hasRole("tenant_sql_query_execute", "SQL_EXECUTE")).isTrue();
                assertThat(policy.hasRole("tenant_document_search", "DOCUMENT_SEARCH")).isTrue();
                assertThat(policy.hasRole("tenant_sql_datasource_asset_query", "ASSET_DISCOVERY")).isTrue();
                assertThat(policy.hasRole("tenant_python_analysis_query", "TEMPLATE_DISCOVERY")).isTrue();
                assertThat(policy.dialectFromTemplateId("POSTGRES_TABLE_METADATA")).isEqualTo("postgresql");
                assertThat(policy.explicitEnvironment("in the PROD cluster")).isEqualTo("PROD");
                assertThat(policy.isTableScopedTemplate("MYSQL_TABLE_METADATA")).isTrue();
                assertThat(policy.canonicalToolName("mcp_chatchat_mcp_server_sql_query_execute"))
                    .isEqualTo("sql_query_execute");
                assertThat(mapper.readTree(seed("mysql"))).isEqualTo(mapper.readTree(seed("h2")));
                assertThat(mapper.readTree(seed("postgresql"))).isEqualTo(mapper.readTree(seed("h2")));
                for (String dialect : new String[] { "h2", "mysql", "postgresql" }) {
                    assertThat(mapper.readTree(extractSeed(initSchema(dialect))))
                        .isEqualTo(mapper.readTree(seed(dialect)));
                }
            }
        }
    }

    private String seed(String dialect) throws Exception {
        return extractSeed(migration(dialect));
    }

    private String extractSeed(String sql) {
        String marker = "('default', '";
        int start = sql.indexOf(marker);
        int end = sql.indexOf("');", start);
        if (start < 0 || end < 0) throw new IllegalStateException("Runtime semantic policy seed missing");
        return sql.substring(start + marker.length(), end);
    }

    private String initSchema(String dialect) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path path = cwd.resolve("database/init/" + dialect + "/chatchat-api.sql");
        if (!Files.exists(path)) path = cwd.resolve("../database/init/" + dialect + "/chatchat-api.sql");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String migration(String dialect) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path path = cwd.resolve("database/migration/" + dialect
            + "/V20261009_01__agent_runtime_semantic_policy.sql");
        if (!Files.exists(path)) path = cwd.resolve("../database/migration/" + dialect
            + "/V20261009_01__agent_runtime_semantic_policy.sql");
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
