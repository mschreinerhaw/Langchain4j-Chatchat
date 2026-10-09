package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeSemanticPolicyTest {
    @Test
    void businessVocabularyComesFromPublishedPolicy() {
        Map<String, Object> values = new java.util.LinkedHashMap<>(Map.of(
            "toolRules", List.of(
                Map.of("role", "SQL_EXECUTE", "mode", "SUFFIX", "value", "graph_query_execute"),
                Map.of("role", "WEB_DISCOVERY", "mode", "CONTAINS", "value", "site_search",
                    "exclude", "search_and_extract"),
                Map.of("role", "GRAPH_TEMPLATE_DISCOVERY", "mode", "REGEX",
                    "value", ".*graph.*template_(query|search)$")),
            "dialectAliases", Map.of("graphdb", "cypher"),
            "dialectContains", List.of(Map.of("needle", "graph", "canonical", "cypher")),
            "templateDialectPrefixes", Map.of("GRAPH_", "cypher"),
            "environmentAliases", Map.of("SANDBOX", "DEV"),
            "explicitEnvironmentPatterns", List.of("environment\\s*[:=]\\s*(SANDBOX)"),
            "discoveryRoles", Map.of("SQL_EXECUTE", "GRAPH_TEMPLATE_DISCOVERY"),
            "discoveryTargetKinds", Map.of("SQL_EXECUTE", "graph"),
            "tableScopedTemplateSuffixes", List.of("_NODE_METADATA")));
        values.put("toolNamePrefixes", List.of("mcp_", "tenant_"));
        values.put("protocolStopWords", List.of("query", "execute"));
        RuntimeSemanticPolicy policy = RuntimeSemanticPolicy.from(values);

        assertThat(policy.hasRole("tenant_graph_query_execute", "SQL_EXECUTE")).isTrue();
        assertThat(policy.hasRole("sql_query_execute", "SQL_EXECUTE")).isFalse();
        assertThat(policy.hasRole("tenant_site_search", "WEB_DISCOVERY")).isTrue();
        assertThat(policy.hasRole("tenant_site_search_and_extract", "WEB_DISCOVERY")).isFalse();
        assertThat(policy.relatedDiscoveryTool("tenant_graph_query_execute",
            List.of("tenant_graph_template_query"))).isEqualTo("tenant_graph_template_query");
        assertThat(policy.discoveryTargetKind("tenant_graph_query_execute")).isEqualTo("graph");
        assertThat(policy.normalizeDialect("GraphDB")).isEqualTo("cypher");
        assertThat(policy.normalizeDialect("graphdb-v2")).isEqualTo("cypher");
        assertThat(policy.dialectFromTemplateId("GRAPH_NODE_METADATA")).isEqualTo("cypher");
        assertThat(policy.isTableScopedTemplate("GRAPH_NODE_METADATA")).isTrue();
        assertThat(policy.canonicalToolName("mcp_tenant_graph_query_execute"))
            .isEqualTo("graph_query_execute");
        assertThat(policy.protocolTokens("mcp_tenant_graph_query_execute")).containsExactly("graph");
        assertThat(policy.explicitEnvironment("environment: sandbox")).isEqualTo("DEV");
        assertThat(RuntimeSemanticPolicy.empty().hasRole("sql_query_execute", "SQL_EXECUTE")).isFalse();
    }

    @Test
    void publishedRolesOverrideCompatibilityNameRulesForExactTool() {
        RuntimeSemanticPolicy policy = RuntimeSemanticPolicy.from(Map.of(
            "schemaVersion", RuntimeSemanticPolicy.SCHEMA_VERSION,
            "policyVersion", "2",
            "toolRules", List.of(Map.of("role", "SQL_EXECUTE", "mode", "SUFFIX",
                "value", "sql_query_execute"))))
            .withPublishedRoles(Map.of("tenant_sql_query_execute", java.util.Set.of("GRAPH_EXECUTE")));

        assertThat(policy.policyVersion()).isEqualTo("2");
        assertThat(policy.hasRole("tenant_sql_query_execute", "SQL_EXECUTE")).isFalse();
        assertThat(policy.hasRole("tenant_sql_query_execute", "GRAPH_EXECUTE")).isTrue();
        assertThat(policy.hasRole("legacy_sql_query_execute", "SQL_EXECUTE")).isTrue();
    }
}
