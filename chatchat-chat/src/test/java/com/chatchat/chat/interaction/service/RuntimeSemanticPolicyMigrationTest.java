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
                for (int pass = 0; pass < 2; pass++) {
                    for (String sql : h2.split(";\\s*")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
            }
            try (var result = connection.createStatement().executeQuery(
                "select policy_json from agent_runtime_semantic_policy where policy_key='default'")) {
                assertThat(result.next()).isTrue();
                Map<String, Object> values = mapper.readValue(result.getString(1), new TypeReference<>() { });
                RuntimeSemanticPolicy policy = RuntimeSemanticPolicy.from(values);
                assertThat(policy.policyVersion()).isEqualTo("1");
                assertThat(values.get("schemaVersion")).isEqualTo(RuntimeSemanticPolicy.SCHEMA_VERSION);
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

    @Test
    void versionMigrationPreservesExistingPolicyAndIsRepeatable() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:runtime_semantic_policy_upgrade")) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table agent_runtime_semantic_policy (policy_key varchar(64) primary key, policy_json text not null)");
                statement.execute("insert into agent_runtime_semantic_policy values ('default', '{\"toolRules\":[],\"custom\":true}')");
                String migration = Files.readString(migrationPath("h2", "V20261009_03__version_runtime_semantic_policy.sql"));
                statement.execute(migration);
                statement.execute(migration);
            }
            try (var result = connection.createStatement().executeQuery(
                "select policy_json from agent_runtime_semantic_policy where policy_key='default'")) {
                assertThat(result.next()).isTrue();
                Map<String, Object> values = mapper.readValue(result.getString(1), new TypeReference<>() { });
                assertThat(values).containsEntry("custom", true).containsEntry("policyVersion", "1");
            }
        }
    }

    @Test
    void legacyToolCaptureDoesNotExpandOnRepeat() throws Exception {
        String migration = Files.readString(migrationPath("h2", "V20261009_04__capture_legacy_tool_semantics.sql"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:runtime_legacy_tool_capture")) {
            try (var statement = connection.createStatement()) {
                statement.execute("create table mcp_tool (local_tool_name varchar(256))");
                statement.execute("insert into mcp_tool values ('old_executor')");
                for (String sql : migration.split(";\\s*")) if (!sql.isBlank()) statement.execute(sql);
                statement.execute("insert into mcp_tool values ('new_executor')");
                for (String sql : migration.split(";\\s*")) if (!sql.isBlank()) statement.execute(sql);
            }
            try (var result = connection.createStatement().executeQuery(
                "select tool_name from agent_runtime_legacy_tool")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("old_executor");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private String seed(String dialect) throws Exception {
        return extractSeed(migration(dialect));
    }

    private String extractSeed(String sql) {
        String marker = "'default', '";
        int start = sql.indexOf(marker);
        int end = sql.indexOf("' where not exists", start);
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
        return Files.readString(migrationPath(dialect, "V20261009_01__agent_runtime_semantic_policy.sql"), StandardCharsets.UTF_8);
    }

    private Path migrationPath(String dialect, String filename) {
        Path cwd = Path.of("").toAbsolutePath();
        Path path = cwd.resolve("database/migration/" + dialect + "/" + filename);
        if (!Files.exists(path)) path = cwd.resolve("../database/migration/" + dialect
            + "/" + filename);
        return path;
    }
}
