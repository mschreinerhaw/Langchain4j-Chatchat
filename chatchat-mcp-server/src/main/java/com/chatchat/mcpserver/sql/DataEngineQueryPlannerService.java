package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DataEngineQueryPlannerService {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final MetadataResolverService metadataResolverService;
    private final MetadataIndexService metadataIndexService;
    private final TableSemanticMatcher semanticMatcher;
    private final JoinGraphBuilder joinGraphBuilder;
    private final CostModelRouter costModelRouter;

    public QueryPlan plan(Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(text(request.get("datasourceId")));
        String question = firstText(text(request.get("question")), text(request.get("intent")), text(request.get("purpose")));
        List<String> requestedTables = requestedTables(request);
        List<TableLocation> tables = resolveTables(datasource, question, requestedTables);
        JoinGraph joinGraph = joinGraphBuilder.build(datasource, tables);
        Map<String, Object> costModel = costModelRouter.evaluate(datasource, tables, joinGraph);
        List<PlanNode> steps = buildSteps(datasource, question, tables, joinGraph);
        return new QueryPlan(
            "query_plan.v4",
            question,
            strategy(steps, joinGraph),
            steps,
            joinGraph,
            costModel,
            diagnostics(datasource, request, requestedTables, tables)
        );
    }

    private List<TableLocation> resolveTables(SqlDatasourceConfig datasource, String question, List<String> requestedTables) {
        LinkedHashSet<TableLocation> resolved = new LinkedHashSet<>();
        for (String table : requestedTables) {
            TableResolution resolution = metadataResolverService.resolveTable(datasource, table, null);
            if (resolution.selectedSchema() != null && resolution.selectedTable() != null) {
                resolved.add(new TableLocation(
                    datasource.getId(),
                    resolution.selectedSchema(),
                    resolution.selectedSchema(),
                    resolution.selectedTable(),
                    "BASE TABLE",
                    null,
                    resolution.confidence()
                ));
            } else {
                resolved.addAll(resolution.candidates().stream().limit(3).toList());
            }
        }
        if (!resolved.isEmpty()) {
            return resolved.stream().limit(8).toList();
        }
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion == null) {
            return List.of();
        }
        return metadataIndexService.allTables(datasource).stream()
            .map(table -> new TableLocation(
                table.datasourceId(),
                table.database(),
                table.schema(),
                table.table(),
                table.tableType(),
                table.tableRows(),
                questionSimilarity(normalizedQuestion, table.table())
            ))
            .filter(table -> table.score() > 0.0)
            .sorted(Comparator.comparingDouble(TableLocation::score).reversed())
            .limit(8)
            .toList();
    }

    private List<PlanNode> buildSteps(SqlDatasourceConfig datasource, String question,
                                      List<TableLocation> tables, JoinGraph joinGraph) {
        List<PlanNode> nodes = new ArrayList<>();
        int index = 1;
        for (TableLocation table : tables) {
            String id = "step_" + index++;
            nodes.add(new PlanNode(
                id,
                "SELECT",
                datasource.getId(),
                table.database(),
                table.table(),
                "SELECT * FROM " + table.database() + "." + table.table() + " LIMIT ${maxRows}",
                List.of(),
                mapOf(
                    "tableScore", table.score(),
                    "columnCount", metadataIndexService.columns(datasource, table).size()
                )
            ));
        }
        if (joinGraph != null && joinGraph.edges() != null && !joinGraph.edges().isEmpty()) {
            JoinEdge edge = joinGraph.edges().get(0);
            nodes.add(new PlanNode(
                "step_" + index++,
                "JOIN",
                datasource.getId(),
                null,
                null,
                edge.leftTable() + " JOIN " + edge.rightTable()
                    + " ON " + edge.leftTable() + "." + edge.leftColumn()
                    + " = " + edge.rightTable() + "." + edge.rightColumn(),
                nodes.stream().filter(node -> "SELECT".equals(node.type())).map(PlanNode::id).toList(),
                mapOf("confidence", edge.confidence(), "reason", edge.reason())
            ));
        }
        if (requiresAggregation(question)) {
            List<String> dependencies = nodes.isEmpty()
                ? List.of()
                : List.of(nodes.get(nodes.size() - 1).id());
            nodes.add(new PlanNode(
                "step_" + index,
                "AGG",
                datasource.getId(),
                null,
                null,
                aggregationFragment(question),
                dependencies,
                mapOf("intent", "trend_or_metric_analysis")
            ));
        }
        return nodes;
    }

    private String strategy(List<PlanNode> steps, JoinGraph joinGraph) {
        boolean hasJoin = joinGraph != null && joinGraph.edges() != null && !joinGraph.edges().isEmpty();
        long rootSelects = steps == null ? 0 : steps.stream()
            .filter(step -> "SELECT".equals(step.type()) && (step.dependencies() == null || step.dependencies().isEmpty()))
            .count();
        if (rootSelects > 1 && hasJoin) {
            return "PARALLEL_SELECT_THEN_JOIN";
        }
        if (hasJoin) {
            return "JOIN_DAG";
        }
        return "SINGLE_PATH";
    }

    private Map<String, Object> diagnostics(SqlDatasourceConfig datasource, Map<String, Object> request,
                                            List<String> requestedTables, List<TableLocation> tables) {
        return mapOf(
            "schemaVersion", "data_engine_planner_diagnostics.v1",
            "datasource", mapOf(
                "id", datasource.getId(),
                "name", datasource.getName(),
                "toolName", datasource.getToolName(),
                "environment", datasource.getEnvironment(),
                "databaseType", datasource.getDatabaseType()
            ),
            "requestedTables", requestedTables,
            "resolvedTables", tables.stream().map(TableLocation::toDiagnostic).toList(),
            "executionContext", request.getOrDefault("executionContext", Map.of()),
            "plannerVersion", "mcp_data_engine_v4"
        );
    }

    private List<String> requestedTables(Map<String, Object> request) {
        Object tables = request.get("tables");
        if (tables instanceof List<?> list) {
            return list.stream()
                .map(this::text)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        }
        Object table = firstObject(request, "table", "tableName", "table_name");
        if (table != null) {
            return List.of(String.valueOf(table));
        }
        return List.of();
    }

    private double questionSimilarity(String normalizedQuestion, String table) {
        String normalizedTable = normalize(table);
        if (normalizedTable == null) {
            return 0.0;
        }
        if (normalizedQuestion.contains(normalizedTable)) {
            return 0.9;
        }
        double best = 0.0;
        for (String token : questionTokens(normalizedQuestion)) {
            best = Math.max(best, semanticMatcher.similarity(token, normalizedTable));
        }
        return best >= 0.4 ? best * 0.8 : 0.0;
    }

    private Set<String> questionTokens(String question) {
        Set<String> tokens = new LinkedHashSet<>();
        if (question == null) {
            return tokens;
        }
        for (String token : question.split("[^a-z0-9_]+")) {
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean requiresAggregation(String question) {
        String normalized = normalize(question);
        return normalized != null && (
            normalized.contains("trend")
                || normalized.contains("趋势")
                || normalized.contains("count")
                || normalized.contains("sum")
                || normalized.contains("avg")
                || normalized.contains("统计")
                || normalized.contains("分析")
        );
    }

    private String aggregationFragment(String question) {
        String normalized = normalize(question);
        if (normalized != null && (normalized.contains("trend") || normalized.contains("趋势"))) {
            return "GROUP BY time_bucket/month and compute trend metrics";
        }
        return "GROUP BY selected dimensions and compute aggregate metrics";
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

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
