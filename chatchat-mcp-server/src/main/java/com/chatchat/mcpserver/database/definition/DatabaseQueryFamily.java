package com.chatchat.mcpserver.database.definition;

import java.util.List;
import java.util.Locale;

/** Query families share storage and execution, but expose separate inherited scopes. */
public final class DatabaseQueryFamily {
    public static final String RELATIONAL_PARENT = "database_query_template_query";
    public static final String TRINO_PARENT = "trino_query_template_query";
    public static final String NEO4J_PARENT = "neo4j_query_template_query";
    public static final String OPENSEARCH_PARENT = "opensearch_query_template_query";
    public static final String ELASTICSEARCH_PARENT = "elasticsearch_query_template_query";
    public static final List<String> PARENTS = List.of(RELATIONAL_PARENT, TRINO_PARENT, NEO4J_PARENT, OPENSEARCH_PARENT, ELASTICSEARCH_PARENT);
    private DatabaseQueryFamily() { }
    public static String fromDatabaseType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "trino", "neo4j", "opensearch", "elasticsearch" -> value;
            default -> "relational";
        };
    }
    public static String forParent(String toolName) {
        if (toolName == null) return null;
        return switch (toolName) {
            case RELATIONAL_PARENT -> "relational";
            case TRINO_PARENT -> "trino";
            case NEO4J_PARENT -> "neo4j";
            case OPENSEARCH_PARENT -> "opensearch";
            case ELASTICSEARCH_PARENT -> "elasticsearch";
            default -> null;
        };
    }
}
