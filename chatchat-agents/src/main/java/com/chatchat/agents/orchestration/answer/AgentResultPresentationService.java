package com.chatchat.agents.orchestration.answer;

import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts tool output into stable user-facing visualization and evidence models. */
public final class AgentResultPresentationService {
    private static final int TOOL_DATA_INLINE_CELL_LIMIT = 240;
    private static final int TOOL_DATA_MARKDOWN_ROW_LIMIT = 20;
    private final ObjectMapper objectMapper;
    public AgentResultPresentationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Map<String, Object> toolResultVisualizationSpec(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return Map.of();
        }
        for (InteractionToolTrace trace : traces) {
            if (trace == null || !trace.isSuccess()) {
                continue;
            }
            Map<String, Object> output = parseObject(trace.getOutput());
            if (output.isEmpty()) {
                continue;
            }
            Map<String, Object> data = firstTabularData(output);
            if (data.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> rows = rowMaps(data.get("rows"), data.get("columns"));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = columns(data.get("columns"), rows);
            if (columns.isEmpty()) {
                continue;
            }
            int rowCount = firstInt(data.get("rowCount"), data.get("total"), data.get("count"), rows.size());
            String title = firstNonBlank(stringValue(data.get("title")), "查询结果明细");
            Map<String, Object> tableSpec = tableVisualizationSpec(title, columns, rows, rowCount, trace);
            Map<String, Object> chartSpec = chartVisualizationSpec(title, columns, rows);
            return chartSpec.isEmpty() ? tableSpec : panelVisualizationSpec(title, chartSpec, tableSpec, trace);
        }
        return Map.of();
    }

    public boolean supportsStructuredResultPresentation(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        Object rawContract = metadata.get("responseContract");
        if (!(rawContract instanceof Map<?, ?> contract)) {
            return false;
        }
        String version = stringValue(contract.get("version"));
        return version != null && version.startsWith("response-contract-v");
    }

    private Map<String, Object> tableVisualizationSpec(String title,
                                                       List<String> columns,
                                                       List<Map<String, Object>> rows,
                                                       int rowCount,
                                                       InteractionToolTrace trace) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("columns", columns);
        dataset.put("rows", rows);
        dataset.put("rowCount", rowCount);
        dataset.put("displayedRowCount", rows.size());

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v1");
        spec.put("type", "table");
        spec.put("title", title);
        spec.put("analysisType", "tool_result_rows");
        spec.put("dataset", dataset);
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "table"));
        spec.put("sourceTool", firstNonBlank(trace.getToolName(), ""));
        spec.put("sourceDisplayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        return spec;
    }

    private Map<String, Object> panelVisualizationSpec(String title,
                                                       Map<String, Object> chartSpec,
                                                       Map<String, Object> tableSpec,
                                                       InteractionToolTrace trace) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v2");
        spec.put("type", "panel");
        spec.put("title", title);
        spec.put("analysisType", "tool_result_visualization");
        spec.put("layout", "stack");
        spec.put("dataset", tableSpec.get("dataset"));
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "panel"));
        spec.put("sourceTool", firstNonBlank(trace.getToolName(), ""));
        spec.put("sourceDisplayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        spec.put("blocks", List.of(
            Map.of("id", "chart", "type", "chart", "title", chartSpec.get("title"), "spec", chartSpec),
            Map.of("id", "table", "type", "table", "title", tableSpec.get("title"), "spec", tableSpec)
        ));
        return spec;
    }

    private Map<String, Object> chartVisualizationSpec(String title,
                                                       List<String> columns,
                                                       List<Map<String, Object>> rows) {
        if (rows == null || rows.size() < 2 || columns == null || columns.isEmpty()) {
            return Map.of();
        }
        String timeKey = columns.stream()
            .filter(column -> isTimeColumn(column, rows))
            .findFirst()
            .orElse(null);
        List<String> numericColumns = columns.stream()
            .filter(column -> !column.equals(timeKey))
            .filter(column -> !isIdentifierColumn(column))
            .filter(column -> rows.stream().anyMatch(row -> numberValue(row.get(column)) != null))
            .limit(4)
            .toList();
        if (!numericColumns.isEmpty()) {
            String xKey = firstNonBlank(timeKey, firstNonBlank(firstCategoricalColumn(columns, rows), columns.get(0)));
            return chartVisualizationSpec(
                title,
                timeKey == null ? "bar" : "line",
                timeKey == null ? "comparison" : "trend",
                xKey,
                numericColumns.stream().map(column -> Map.of("name", column, "yKey", column)).toList(),
                rows
            );
        }

        String categoryKey = firstCategoricalColumn(columns, rows);
        if (categoryKey == null) {
            return Map.of();
        }
        List<Map<String, Object>> countedRows = categoryCountRows(rows, categoryKey);
        if (!isReadableCategoryDistribution(rows, countedRows)) {
            return Map.of();
        }
        return chartVisualizationSpec(
            title + "分布",
            countedRows.size() <= 8 ? "pie" : "bar",
            "distribution",
            categoryKey,
            List.of(Map.of("name", "数量", "yKey", "count")),
            countedRows
        );
    }

    private Map<String, Object> chartVisualizationSpec(String title,
                                                       String chartType,
                                                       String analysisType,
                                                       String xKey,
                                                       List<Map<String, String>> series,
                                                       List<Map<String, Object>> rows) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("xKey", xKey);
        dataset.put("series", series);
        dataset.put("rows", rows);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", "v1");
        spec.put("type", "chart");
        spec.put("chartType", chartType);
        spec.put("title", title);
        spec.put("analysisType", analysisType);
        spec.put("dataset", dataset);
        spec.put("ui", Map.of("allowSwitch", true, "defaultView", "chart"));
        spec.put("insight", Map.of("summary", "已根据结构化工具结果自动生成图形表达。"));
        return spec;
    }

    private String firstCategoricalColumn(List<String> columns, List<Map<String, Object>> rows) {
        for (String column : columns) {
            if (isIdentifierColumn(column) || isTimeColumn(column, rows)) {
                continue;
            }
            if (rows.stream().anyMatch(row -> numberValue(row.get(column)) == null && nonBlankString(row.get(column)))) {
                return column;
            }
        }
        return null;
    }

    private List<Map<String, Object>> categoryCountRows(List<Map<String, Object>> rows, String categoryKey) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = stringValue(row.get(categoryKey));
            if (key == null || key.isBlank()) {
                key = "未分类";
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(categoryKey, entry.getKey());
                row.put("count", entry.getValue());
                return row;
            })
            .toList();
    }

    private boolean isReadableCategoryDistribution(List<Map<String, Object>> rows,
                                                   List<Map<String, Object>> countedRows) {
        if (rows == null || rows.size() < 2 || countedRows == null || countedRows.size() < 2) {
            return false;
        }
        int categoryCount = countedRows.size();
        if (categoryCount > 20) {
            return false;
        }
        // One category per row is an identifier listing, not a useful distribution.
        return categoryCount < rows.size();
    }

    private boolean isTimeColumn(String column, List<Map<String, Object>> rows) {
        String normalized = column == null ? "" : column.toLowerCase();
        if (normalized.matches(".*(date|time|month|year|day|week|quarter).*")
            || normalized.contains("日期")
            || normalized.contains("时间")) {
            return true;
        }
        return rows.stream()
            .map(row -> stringValue(row.get(column)))
            .filter(value -> value != null && !value.isBlank())
            .limit(5)
            .anyMatch(value -> value.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")
                || value.matches("\\d{8}")
                || value.matches("\\d{2}:\\d{2}(:\\d{2})?.*"));
    }

    private boolean isIdentifierColumn(String column) {
        String normalized = column == null ? "" : column.toLowerCase();
        return normalized.matches(".*(id|code|no|uuid|serial|key|hash|token).*")
            || normalized.contains("代码")
            || normalized.contains("编号")
            || normalized.contains("标识");
    }

    private Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replace(",", "").replace("%", "").trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public List<Map<String, Object>> toolResultEvidence(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (InteractionToolTrace trace : traces) {
            if (trace == null) {
                continue;
            }
            Map<String, Object> item = toolEvidence(trace);
            if (!item.isEmpty()) {
                evidence.add(item);
            }
        }
        return List.copyOf(evidence);
    }

    private Map<String, Object> toolEvidence(InteractionToolTrace trace) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("toolName", firstNonBlank(trace.getToolName(), ""));
        item.put("displayName", firstNonBlank(trace.getDisplayName(), trace.getToolName()));
        item.put("success", trace.isSuccess());
        item.put("durationMs", trace.getDurationMs());
        if (trace.getErrorMessage() != null && !trace.getErrorMessage().isBlank()) {
            item.put("errorMessage", trace.getErrorMessage());
        }

        Map<String, Object> output = parseObject(trace.getOutput());
        if (output.isEmpty()) {
            String preview = previewText(trace.getOutput());
            if (preview.isBlank()) {
                return item;
            }
            item.put("evidenceType", "text");
            item.put("outputPreview", preview);
            return item;
        }

        Map<String, Object> batchEvidence = batchResultSetEvidence(output);
        if (!batchEvidence.isEmpty()) {
            item.putAll(batchEvidence);
            if ("FAILED".equalsIgnoreCase(stringValue(batchEvidence.get("batchStatus")))) {
                item.put("success", false);
            }
            return compactEvidence(item);
        }

        Map<String, Object> table = firstTabularData(output);
        if (!table.isEmpty()) {
            List<Map<String, Object>> rows = rowMaps(table.get("rows"), table.get("columns"));
            List<String> columns = columns(table.get("columns"), rows);
            item.put("evidenceType", "tabular");
            item.put("columns", columns);
            item.put("rowCount", firstInt(table.get("rowCount"), table.get("total"), table.get("count"), rows.size()));
            item.put("returnedRowCount", rows.size());
            item.put("sampleRows", rows);
            return item;
        }

        Map<String, Object> data = primaryData(output);
        if (isLinuxEvidence(trace.getToolName(), data)) {
            item.put("evidenceType", "linux_command");
            item.put("exitCode", firstPresent(data.get("exitCode"), output.get("exitCode")));
            item.put("commandSuccess", firstPresent(data.get("commandSuccess"), output.get("commandSuccess")));
            item.put("transportSuccess", firstPresent(data.get("transportSuccess"), output.get("transportSuccess")));
            item.put("failedStepIndex", firstPresent(data.get("failedStepIndex"), output.get("failedStepIndex")));
            item.put("stdoutPreview", previewText(firstNonBlank(stringValue(data.get("stdout")), stringValue(output.get("stdout")))));
            item.put("stderrPreview", previewText(firstNonBlank(stringValue(data.get("stderr")), stringValue(output.get("stderr")))));
            item.put("stepCount", listSize(data.get("steps")));
            return compactEvidence(item);
        }

        if (isHttpEvidence(data)) {
            item.put("evidenceType", "http_response");
            item.put("statusCode", firstPresent(data.get("statusCode"), output.get("statusCode")));
            item.put("bodyPreview", previewStructured(firstPresent(data.get("body"), output.get("body"))));
            item.put("rawBodyPreview", previewText(firstNonBlank(stringValue(data.get("rawBody")), stringValue(output.get("rawBody")))));
            return compactEvidence(item);
        }

        item.put("evidenceType", "json");
        item.put("schemaVersion", firstPresent(output.get("schemaVersion"), output.get("dataSchema")));
        item.put("payloadType", output.get("payloadType"));
        item.put("keys", new ArrayList<>(output.keySet()));
        item.put("outputPreview", previewStructured(output));
        return compactEvidence(item);
    }

    private Map<String, Object> batchResultSetEvidence(Map<String, Object> output) {
        Object rawResults = output == null ? null : output.get("results");
        if (!(rawResults instanceof List<?> results) || results.isEmpty()
            || (!output.containsKey("batchId") && !output.containsKey("cardinality"))) {
            return Map.of();
        }
        List<String> templateIds = new ArrayList<>();
        List<Map<String, Object>> resultSetEvidence = new ArrayList<>();
        int returnedRows = 0;
        int usableResultSets = 0;
        for (Object value : results) {
            if (!(value instanceof Map<?, ?> rawResult)) {
                continue;
            }
            Map<String, Object> result = copyMap(rawResult);
            String templateId = firstNonBlank(
                stringValue(result.get("templateId")), stringValue(result.get("templateCode")));
            if (templateId != null && !templateId.isBlank()) {
                templateIds.add(templateId);
            }
            if (Boolean.TRUE.equals(result.get("evidenceUsable"))) {
                usableResultSets++;
            }
            Object rawOutput = firstPresent(result.get("output"), result.get("finding"));
            Map<String, Object> childOutput = rawOutput instanceof Map<?, ?> map
                ? copyMap(map)
                : rawOutput instanceof String text ? parseObject(text) : Map.of();
            Map<String, Object> table = firstTabularData(childOutput);
            if (!table.isEmpty()) {
                returnedRows += rowMaps(table.get("rows"), table.get("columns")).size();
            }
            Map<String, Object> childEvidence = new LinkedHashMap<>();
            childEvidence.put("templateId", templateId);
            childEvidence.put("callId", result.get("callId"));
            childEvidence.put("status", result.get("status"));
            childEvidence.put("success", !"FAILED".equalsIgnoreCase(stringValue(result.get("status"))));
            childEvidence.put("outputPreview", previewStructured(
                rawOutput == null ? result : rawOutput));
            resultSetEvidence.add(compactEvidence(childEvidence));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceType", "result_set_batch");
        evidence.put("resultSetCount", results.size());
        evidence.put("usableResultSetCount", usableResultSets);
        evidence.put("returnedRowCount", returnedRows);
        evidence.put("templateIds", List.copyOf(templateIds));
        evidence.put("resultSetEvidence", List.copyOf(resultSetEvidence));
        if (output.get("status") != null) {
            evidence.put("batchStatus", output.get("status"));
        }
        return Map.copyOf(evidence);
    }

    private Map<String, Object> primaryData(Map<String, Object> output) {
        for (String key : List.of("data", "result", "payload", "structuredContent")) {
            Object value = output.get(key);
            if (value instanceof Map<?, ?> map) {
                return copyMap(map);
            }
        }
        return output == null ? Map.of() : output;
    }

    private boolean isLinuxEvidence(String toolName, Map<String, Object> data) {
        String normalizedTool = firstNonBlank(toolName, "").toLowerCase(Locale.ROOT);
        if (normalizedTool.endsWith("linux_command_execute")
            || normalizedTool.endsWith("ssh_linux_execute")) {
            return true;
        }
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey("commandSuccess")
            || data.containsKey("transportSuccess")
            || data.containsKey("steps")
            || "ssh_command".equalsIgnoreCase(stringValue(data.get("kind")))
            || firstNonBlank(stringValue(data.get("dataSchema")), "")
                .toLowerCase(Locale.ROOT).startsWith("ssh_");
    }

    private boolean isHttpEvidence(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey("statusCode")
            || data.containsKey("body")
            || data.containsKey("rawBody");
    }

    private Map<String, Object> compactEvidence(Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        item.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                return;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                return;
            }
            result.put(key, value);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object value = objectMapper.readValue(text, Object.class);
            return value instanceof Map<?, ?> map ? copyMap((Map<?, ?>) map) : Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> firstTabularData(Map<String, Object> output) {
        Map<String, Object> direct = tabularData(output);
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> batch = batchTabularData(output);
        if (!batch.isEmpty()) {
            return batch;
        }
        for (String key : List.of("result", "data", "dataset", "payload", "body", "structuredContent")) {
            Object value = output.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = firstTabularData(copyMap(map));
                if (!nested.isEmpty()) {
                    return nested;
                }
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                Map<String, Object> nested = tabularData(Map.of("records", list));
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return Map.of();
    }

    private Map<String, Object> batchTabularData(Map<String, Object> output) {
        Object rawResults = output == null ? null : output.get("results");
        if (!(rawResults instanceof List<?> results) || results.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> combinedRows = new ArrayList<>();
        Set<String> combinedColumns = new LinkedHashSet<>();
        combinedColumns.add("DIAGNOSTIC_CHECK");
        combinedColumns.add("TEMPLATE_CODE");
        int totalRows = 0;
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> rawItem)) {
                continue;
            }
            Map<String, Object> child = copyMap(rawItem);
            Object rawOutput = child.get("output");
            Map<String, Object> childOutput = rawOutput instanceof Map<?, ?> map
                ? copyMap(map)
                : rawOutput instanceof String text ? parseObject(text) : Map.of();
            Map<String, Object> table = firstTabularData(childOutput);
            if (table.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> rows = rowMaps(table.get("rows"), table.get("columns"));
            if (rows.isEmpty()) {
                continue;
            }
            String checkId = firstNonBlank(
                stringValue(child.get("checkId")),
                firstNonBlank(stringValue(child.get("callId")), "unknown_check")
            );
            String templateCode = firstNonBlank(
                stringValue(child.get("templateCode")),
                firstNonBlank(stringValue(child.get("templateId")), "")
            );
            for (Map<String, Object> row : rows) {
                Map<String, Object> annotated = new LinkedHashMap<>();
                annotated.put("DIAGNOSTIC_CHECK", checkId);
                annotated.put("TEMPLATE_CODE", templateCode);
                annotated.putAll(row);
                combinedRows.add(annotated);
                combinedColumns.addAll(row.keySet());
            }
            totalRows += firstInt(
                table.get("rowCount"), table.get("total"), table.get("count"), rows.size());
        }
        if (combinedRows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "批量诊断结果明细");
        data.put("columns", new ArrayList<>(combinedColumns));
        data.put("rows", combinedRows);
        data.put("rowCount", totalRows);
        return data;
    }

    private Map<String, Object> tabularData(Map<String, Object> value) {
        Object rows = firstPresent(value.get("rows"), firstPresent(value.get("records"), value.get("items")));
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", rows);
        data.put("columns", firstPresent(value.get("columns"), value.get("fields")));
        data.put("rowCount", firstPresent(value.get("rowCount"), firstPresent(value.get("total"), value.get("count"))));
        data.put("title", firstPresent(value.get("title"), firstPresent(value.get("name"), value.get("templateId"))));
        return data;
    }

    private List<Map<String, Object>> rowMaps(Object rowsValue, Object columnsValue) {
        if (!(rowsValue instanceof List<?> values)) {
            return List.of();
        }
        List<String> columns = columnsValue instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                rows.add(copyMap(map));
            } else if (value instanceof List<?> rowValues && !columns.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size() && i < rowValues.size(); i++) {
                    row.put(columns.get(i), rowValues.get(i));
                }
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private List<String> columns(Object columnsValue, List<Map<String, Object>> rows) {
        if (columnsValue instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!columns.contains(key)) {
                    columns.add(key);
                }
            }
        }
        return List.copyOf(columns);
    }

    public Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public String appendToolResultTable(String answer, Map<String, Object> visualizationSpec) {
        if (visualizationSpec == null || visualizationSpec.isEmpty()) {
            return answer == null ? "" : answer;
        }
        String base = answer == null ? "" : answer.trim();
        if (base.contains("## 查询结果明细") || base.contains("## 数据明细")) {
            return base;
        }
        Map<String, Object> dataset = visualizationSpec.get("dataset") instanceof Map<?, ?> map
            ? copyMap(map)
            : Map.of();
        List<String> columns = dataset.get("columns") instanceof List<?> list
            ? list.stream().map(String::valueOf).toList()
            : List.of();
        List<Map<String, Object>> rows = rowMaps(dataset.get("rows"), columns);
        if (columns.isEmpty() || rows.isEmpty()) {
            return base;
        }
        int rowCount = firstInt(dataset.get("rowCount"), rows.size());
        int displayCount = Math.min(rows.size(), TOOL_DATA_MARKDOWN_ROW_LIMIT);
        StringBuilder table = new StringBuilder();
        List<LongTextCell> longTextCells = new ArrayList<>();
        table.append("## 查询结果明细\n\n");
        table.append("已找到 ").append(rowCount).append(" 行数据，下面展示前 ")
            .append(displayCount).append(" 行；完整结构化数据已随结果返回用于表格展示。\n\n");
        table.append("| ");
        for (String column : columns) {
            table.append(escapeTableCell(column)).append(" | ");
        }
        table.append("\n| ");
        for (int i = 0; i < columns.size(); i++) {
            table.append("--- | ");
        }
        table.append("\n");
        int displayedRowIndex = 0;
        for (Map<String, Object> row : rows.subList(0, displayCount)) {
            displayedRowIndex++;
            table.append("| ");
            for (String column : columns) {
                Object value = row.get(column);
                if (value instanceof Map<?, ?> map) {
                    table.append(escapeTableCell("[结构化对象：" + map.size() + " 个字段]"))
                        .append(" | ");
                } else if (value instanceof List<?> list) {
                    table.append(escapeTableCell("[结构化数组：" + list.size() + " 项]"))
                        .append(" | ");
                } else if (isLongTextCell(value)) {
                    String text = String.valueOf(value);
                    longTextCells.add(new LongTextCell(displayedRowIndex, column, text));
                    table.append(escapeTableCell(longTextReference(displayedRowIndex, column, text.length()))).append(" | ");
                } else {
                    table.append(escapeTableCell(value)).append(" | ");
                }
            }
            table.append("\n");
        }
        appendLongTextCells(table, longTextCells);
        return base.isBlank() ? table.toString().trim() : base + "\n\n" + table.toString().trim();
    }

    private boolean isLongTextCell(Object value) {
        if (!(value instanceof CharSequence sequence)) {
            return false;
        }
        String text = sequence.toString();
        return text.length() > TOOL_DATA_INLINE_CELL_LIMIT || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
    }

    private String longTextReference(int rowNumber, String column, int length) {
        return "[完整内容见下方：第 " + rowNumber + " 行 / " + column + "，" + length + " 字符]";
    }

    private void appendLongTextCells(StringBuilder markdown, List<LongTextCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        markdown.append("\n### 长文本字段完整内容\n\n");
        for (LongTextCell cell : cells) {
            markdown.append("#### 第 ").append(cell.rowNumber()).append(" 行 · ")
                .append(escapeInline(cell.column())).append("\n\n");
            appendFencedText(markdown, cell.value());
            markdown.append("\n");
        }
    }

    private void appendFencedText(StringBuilder markdown, String value) {
        String text = value == null ? "" : value;
        String fence = "```";
        while (text.contains(fence)) {
            fence += "`";
        }
        markdown.append(fence).append("text\n").append(text);
        if (!text.endsWith("\n")) {
            markdown.append("\n");
        }
        markdown.append(fence).append("\n");
    }


    private boolean nonBlankString(Object value) { return value != null && !String.valueOf(value).isBlank(); }
    private String previewText(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "...";
    }
    private String previewStructured(Object value) {
        if (value == null) return "";
        try { return previewText(objectMapper.writeValueAsString(value)); }
        catch (Exception ignored) { return previewText(String.valueOf(value)); }
    }
    private int listSize(Object value) { return value instanceof List<?> list ? list.size() : 0; }
    private int firstInt(Object first, Object second) { return firstInt(first, second, null, 0); }
    private int firstInt(Object first, Object second, Object third, int fallback) {
        for (Object value : new Object[] { first, second, third }) {
            Integer parsed = intValue(value);
            if (parsed != null) return parsed;
        }
        return fallback;
    }
    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
    private String escapeTableCell(Object value) {
        return value == null ? "" : String.valueOf(value).replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }
    private record LongTextCell(int rowNumber, String column, String value) { }
    private String escapeInline(String value) {
        return value == null ? "" : value.replace(String.valueOf((char) 96), "\\`").replace("\r", " ").replace("\n", " ");
    }
    private String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        return copyMap(map);
    }
    private Object firstPresent(Object first, Object second) { return first == null ? second : first; }
    private String firstNonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }
}
