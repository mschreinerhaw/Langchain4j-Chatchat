package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CostModelRouter {

    private final MetadataIndexService metadataIndexService;

    public Map<String, Object> evaluate(SqlDatasourceConfig datasource, List<TableLocation> tables, JoinGraph joinGraph) {
        List<Map<String, Object>> tableCosts = tables == null ? List.of() : tables.stream()
            .map(table -> tableCost(datasource, table))
            .sorted(Comparator.comparingDouble(value -> doubleValue(value.get("cost"))))
            .toList();
        double joinPenalty = joinGraph == null || joinGraph.edges() == null || joinGraph.edges().isEmpty() ? 20.0 : 0.0;
        double totalCost = tableCosts.stream()
            .mapToDouble(value -> doubleValue(value.get("cost")))
            .sum() + joinPenalty;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "cost_model.v1");
        value.put("strategy", "metadata_size_index_join_penalty");
        value.put("totalCost", round(totalCost));
        value.put("joinPenalty", joinPenalty);
        value.put("tableCosts", tableCosts);
        value.put("chosenPath", tableCosts.stream().map(item -> item.get("table")).toList());
        return value;
    }

    private Map<String, Object> tableCost(SqlDatasourceConfig datasource, TableLocation table) {
        List<MetadataColumn> columns = metadataIndexService.columns(datasource, table);
        double dataSize = table.tableRows() == null ? 1000.0 : Math.max(1.0, table.tableRows());
        double sizeCost = Math.log10(dataSize + 1) * 10.0;
        double columnCost = Math.min(20.0, columns.size());
        double indexDiscount = columns.stream().anyMatch(column -> column.columnKey() != null && !column.columnKey().isBlank())
            ? 10.0
            : 0.0;
        double metadataScoreDiscount = table.score() * 8.0;
        double cost = Math.max(1.0, sizeCost + columnCost - indexDiscount - metadataScoreDiscount);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("table", table.database() + "." + table.table());
        value.put("rowEstimate", table.tableRows());
        value.put("columnCount", columns.size());
        value.put("indexAvailable", indexDiscount > 0);
        value.put("metadataScore", table.score());
        value.put("cost", round(cost));
        return value;
    }

    private double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
