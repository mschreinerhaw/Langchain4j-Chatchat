package com.chatchat.mcpserver.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SemanticIrBuilder {

    public SemanticIR build(SqlDatasourceConfig datasource, Map<String, Object> request, List<String> requestedTables) {
        Map<String, Object> safeRequest = request == null ? Map.of() : request;
        String dialect = dialect(datasource, safeRequest);
        String operation = operation(safeRequest, requestedTables);
        String targetLevel = requestedTables == null || requestedTables.isEmpty() ? "DATASOURCE" : "TABLE";
        SemanticOperationNode root = new SemanticOperationNode(
            "root",
            operation,
            targetLevel,
            dialect,
            requestedTables == null ? List.of() : requestedTables,
            mapOf(
                "question", firstText(text(safeRequest.get("question")), text(safeRequest.get("intent")), text(safeRequest.get("purpose"))),
                "datasourceId", datasource == null ? null : datasource.getId(),
                "datasourceName", datasource == null ? null : datasource.getName()
            )
        );
        return new SemanticIR(
            "semantic_ir.v1",
            root,
            constraints(datasource, safeRequest, requestedTables),
            context(datasource, safeRequest)
        );
    }

    private List<Map<String, Object>> constraints(SqlDatasourceConfig datasource,
                                                  Map<String, Object> request,
                                                  List<String> requestedTables) {
        List<Map<String, Object>> values = new ArrayList<>();
        values.add(mapOf("type", "READ_ONLY", "required", true));
        if (datasource != null) {
            values.add(mapOf("type", "DATASOURCE_BOUND", "datasourceId", datasource.getId()));
            values.add(mapOf("type", "DIALECT_BOUND", "dialect", SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType())));
        }
        if (requestedTables != null && !requestedTables.isEmpty()) {
            values.add(mapOf("type", "TABLE_TARGETS", "tables", requestedTables));
        }
        Object maxRows = firstObject(request, "maxRows", "limit");
        if (maxRows != null) {
            values.add(mapOf("type", "ROW_LIMIT", "value", maxRows));
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> context(SqlDatasourceConfig datasource, Map<String, Object> request) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (datasource != null) {
            values.put("datasource", mapOf(
                "id", datasource.getId(),
                "name", datasource.getName(),
                "environment", datasource.getEnvironment(),
                "databaseType", datasource.getDatabaseType()
            ));
        }
        Object executionContext = request.get("executionContext");
        if (executionContext instanceof Map<?, ?> map) {
            values.put("executionContext", new LinkedHashMap<>((Map<String, Object>) map));
        } else {
            values.put("executionContext", Map.of());
        }
        values.put("requestKeys", request.keySet().stream().sorted().toList());
        return values;
    }

    private String operation(Map<String, Object> request, List<String> requestedTables) {
        Object explicit = firstObject(request, "operation", "semanticOperation");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return normalizeOperation(String.valueOf(explicit));
        }
        Object semantic = valueAtPath(request, "semantic", "operation");
        if (semantic != null && !String.valueOf(semantic).isBlank()) {
            return normalizeOperation(String.valueOf(semantic));
        }
        String question = firstText(text(request.get("question")), text(request.get("intent")), text(request.get("purpose")));
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (normalizedQuestion.contains("trend") || normalizedQuestion.contains("join") || normalizedQuestion.contains("metric")
            || normalizedQuestion.contains("分析") || normalizedQuestion.contains("趋势") || normalizedQuestion.contains("统计")) {
            return "ANALYTIC_QUERY";
        }
        return requestedTables == null || requestedTables.isEmpty() ? "DISCOVERY_QUERY" : "TABLE_METADATA_QUERY";
    }

    private String dialect(SqlDatasourceConfig datasource, Map<String, Object> request) {
        Object explicit = firstObject(request, "databaseType", "dbType", "dialect");
        if (explicit == null) {
            explicit = valueAtPath(request, "executionContext", "databaseType");
        }
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return SqlDatasourceConfigService.normalizeDatabaseTypeToken(String.valueOf(explicit));
        }
        return datasource == null ? "generic" : SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
    }

    private Object valueAtPath(Map<String, Object> map, String first, String second) {
        Object child = map == null ? null : map.get(first);
        if (!(child instanceof Map<?, ?> nested)) {
            return null;
        }
        return nested.get(second);
    }

    private Object firstObject(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeOperation(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
