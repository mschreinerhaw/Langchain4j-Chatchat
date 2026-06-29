package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JoinGraphBuilder {

    private final MetadataIndexService metadataIndexService;

    public JoinGraph build(SqlDatasourceConfig datasource, List<TableLocation> tables) {
        if (datasource == null || tables == null || tables.size() < 2) {
            return new JoinGraph("join_graph.v1", List.of(), Map.of());
        }
        List<JoinEdge> edges = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            for (int j = i + 1; j < tables.size(); j++) {
                inferEdge(datasource, tables.get(i), tables.get(j)).stream()
                    .findFirst()
                    .ifPresent(edges::add);
            }
        }
        edges = edges.stream()
            .sorted(Comparator.comparingDouble(JoinEdge::confidence).reversed())
            .toList();
        Map<String, List<JoinEdge>> adjacency = new LinkedHashMap<>();
        for (JoinEdge edge : edges) {
            adjacency.computeIfAbsent(edge.leftTable(), ignored -> new ArrayList<>()).add(edge);
            adjacency.computeIfAbsent(edge.rightTable(), ignored -> new ArrayList<>()).add(edge);
        }
        return new JoinGraph("join_graph.v1", edges, adjacency);
    }

    private List<JoinEdge> inferEdge(SqlDatasourceConfig datasource, TableLocation left, TableLocation right) {
        List<MetadataColumn> leftColumns = metadataIndexService.columns(datasource, left);
        List<MetadataColumn> rightColumns = metadataIndexService.columns(datasource, right);
        List<JoinEdge> edges = new ArrayList<>();
        for (MetadataColumn leftColumn : leftColumns) {
            for (MetadataColumn rightColumn : rightColumns) {
                JoinEdge edge = inferColumnEdge(left, leftColumn, right, rightColumn);
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }
        return edges.stream()
            .sorted(Comparator.comparingDouble(JoinEdge::confidence).reversed())
            .toList();
    }

    private JoinEdge inferColumnEdge(TableLocation left, MetadataColumn leftColumn,
                                     TableLocation right, MetadataColumn rightColumn) {
        String leftName = normalize(leftColumn.name());
        String rightName = normalize(rightColumn.name());
        String leftTable = normalize(left.table());
        String rightTable = normalize(right.table());
        if (leftName == null || rightName == null || leftTable == null || rightTable == null) {
            return null;
        }
        if (leftName.equals(rightTable + "_id") && rightName.equals("id")) {
            return edge(left, leftColumn, right, rightColumn, 0.92, "left_foreign_key_to_right_id");
        }
        if (rightName.equals(leftTable + "_id") && leftName.equals("id")) {
            return edge(left, leftColumn, right, rightColumn, 0.92, "right_foreign_key_to_left_id");
        }
        if (leftName.equals(rightName) && !leftName.equals("id")) {
            return edge(left, leftColumn, right, rightColumn, isIndexed(leftColumn, rightColumn) ? 0.82 : 0.72,
                "same_non_id_column");
        }
        if (leftName.endsWith("_id") && rightName.equals(leftName)) {
            return edge(left, leftColumn, right, rightColumn, 0.70, "matching_fk_name");
        }
        return null;
    }

    private JoinEdge edge(TableLocation left, MetadataColumn leftColumn, TableLocation right,
                          MetadataColumn rightColumn, double confidence, String reason) {
        return new JoinEdge(
            qualifiedTable(left),
            leftColumn.name(),
            qualifiedTable(right),
            rightColumn.name(),
            leftColumn.name() + "=" + rightColumn.name(),
            confidence,
            reason
        );
    }

    private boolean isIndexed(MetadataColumn left, MetadataColumn right) {
        return indexed(left.columnKey()) || indexed(right.columnKey());
    }

    private boolean indexed(String value) {
        return value != null && !value.isBlank();
    }

    private String qualifiedTable(TableLocation table) {
        return table.database() + "." + table.table();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
