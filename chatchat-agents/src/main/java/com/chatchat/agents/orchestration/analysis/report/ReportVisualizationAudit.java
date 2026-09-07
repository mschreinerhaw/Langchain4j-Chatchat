package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Recomputes declarative views from this run's typed evidence. Never executes model code. */
public final class ReportVisualizationAudit {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Result(String markdown, List<Map<String, Object>> checks) { }

    public static String instruction() {
        return """
            Optional visualizations: within the Markdown report you may include fenced json blocks containing
            {"visualizationSpec":{"schemaVersion":"visualization_spec.v2","chartType":"bar",
            "title":"comparison","dataset":{"sourceRef":"returned dataset reference","xKey":"category",
            "series":[{"name":"measure","yKey":"amount"}]}}}.
            Use chartType bar, line (ISO-date x values), pie (nonnegative values), scatter (numeric x/y) or kpi (one value).
            Alternatively use a json:visualization fence with {"intent":"trend|ranking|comparison|composition|metric|relationship",
            "datasetRef":"returned reference","x":"actual field","y":"actual metric","title":"business title"}.
            Runtime chooses the chart, orders rankings and supplies exact source rows.
            Prefer dataRef from the verified computed catalog when it answers the question; otherwise use dataset.sourceRef
            from the returned-data catalog. Select actual field names, keeping their labels and units unchanged.
            dataset.rows is optional. If supplied, every selected row and value must match the referenced source.
            For a new derived view, add transform:{operation:"SUM|AVG|COUNT|SHARE",groupBy:"category",metric:"amount"}
            inside visualizationSpec. Runtime executes the explicitly selected operation over the referenced returned rows.
            Use dataset.xKey equal to groupBy and one series with yKey:"value" for derived views. SHARE means each
            group's SUM divided by the SUM over all returned rows, expressed in percent; it requires nonnegative inputs.
            Only operations explicitly allowed by source metricPolicies are available. Unknown or preAggregated metrics
            cannot be aggregated again. Incomplete projections cannot be aggregated.
            Explain calculation meaning and returned-sample scope in prose. These operations do not establish population
            completeness, causality or accounting semantics. Omit dataset.rows to let Runtime provide exact computed values.
            Keep each graphic next to its supporting discussion; use at most six graphics. No JavaScript, HTML, SQL,
            arbitrary ECharts options or additional audit forms. A failed graphic never replaces the narrative report.
            """;
    }

    public Result audit(String markdown, VerifiedReportDataCatalog catalog) {
        if (markdown == null) return new Result("", List.of());
        StringBuilder result = new StringBuilder();
        List<Map<String, Object>> checks = new ArrayList<>();
        // Walk all fences so JSON examples inside a longer code fence are never interpreted.
        String[] lines = markdown.split("(?<=\n)", -1);
        for (int i = 0; i < lines.length; i++) {
            var opening = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)[\\r\\n]*$").matcher(lines[i]);
            if (!opening.matches()) { result.append(lines[i]); continue; }
            String marker = opening.group(1);
            int end = i + 1;
            Pattern closing = Pattern.compile("^ {0,3}" + Pattern.quote(marker.substring(0, 1))
                + "{" + marker.length() + ",}[ \\t]*[\\r\\n]*$");
            while (end < lines.length && !closing.matcher(lines[end]).matches()) end++;
            if (end == lines.length) { for (; i < lines.length; i++) result.append(lines[i]); break; }
            String block = String.join("", Arrays.copyOfRange(lines, i + 1, end));
            boolean intentFence = opening.group(2).trim().equalsIgnoreCase("json:visualization");
            if (!intentFence && !opening.group(2).trim().equalsIgnoreCase("json")) {
                for (; i <= end; i++) result.append(lines[i]);
                i--;
                continue;
            }
            Map<String, Object> requested = Map.of();
            try {
                if (block.length() > 40000) throw new IllegalArgumentException("VIEW_BUDGET_EXCEEDED");
                Map<String, Object> wrapper = JSON.readValue(block, new TypeReference<>() { });
                if (!intentFence && !wrapper.containsKey("visualizationSpec")) {
                    for (; i <= end; i++) result.append(lines[i]);
                    i--;
                    continue;
                }
                if (checks.size() >= 6) throw new IllegalArgumentException("VIEW_BUDGET_EXCEEDED");
                requested = intentFence ? wrapper : map(wrapper.get("visualizationSpec"));
                requested = compileIntent(requested);
                Map<String, Object> spec = verify(requested, catalog);
                result.append("```json\n").append(ModelProtocolJson.compact(Map.of("visualizationSpec", spec)))
                    .append("\n```\n");
                checks.add(Map.of("status", "VERIFIED", "sourceRef", map(spec.get("dataset")).get("sourceRef"),
                    "scope", "RETURNED_RECORDS_ONLY"));
            } catch (Exception invalid) {
                if (!intentFence && !block.contains("visualizationSpec") && !(invalid instanceof IllegalArgumentException)) {
                    for (; i <= end; i++) result.append(lines[i]);
                    i--;
                    continue;
                }
                result.append("\n> 此图表的数据绑定或计算未通过核验，已省略图表；请参阅正文分析与数据表。\n\n");
                if (checks.size() < 6) result.append(sourceTable(requested, catalog));
                if (checks.size() < 12) checks.add(Map.of("status", "REJECTED", "reason",
                    invalid instanceof IllegalArgumentException ? String.valueOf(invalid.getMessage()) : "INVALID_VISUALIZATION_JSON"));
            }
            i = end;
        }
        return new Result(checks.isEmpty() ? markdown : result.toString(), List.copyOf(checks));
    }

    private Map<String, Object> verify(Map<String, Object> spec, VerifiedReportDataCatalog catalog) {
        String type = text(spec.get("chartType")).toLowerCase(Locale.ROOT);
        require(Set.of("bar", "line", "pie", "scatter", "kpi").contains(type), "UNSUPPORTED_CHART");
        Map<String, Object> dataset = map(spec.get("dataset"));
        String reference = text(spec.get("dataRef"));
        List<Map<String, Object>> rows;
        boolean complete;
        if (!reference.isEmpty()) {
            var computed = catalog.get(reference);
            require(computed != null, "UNKNOWN_DATA_REF");
            rows = computed.rows().isEmpty() && computed.metric() != null
                ? List.of(Map.of("entity", computed.title(), "value", computed.metric())) : computed.rows();
            complete = false; // A computed Top-N slice is never an aggregation population.
        } else {
            reference = text(dataset.get("sourceRef"));
            var source = catalog.dataset(reference);
            require(source != null, "UNKNOWN_SOURCE_REF");
            rows = source.rows();
            complete = source.complete();
        }
        String x = text(dataset.get("xKey"));
        List<Map<String, Object>> series = maps(dataset.get("series"));
        require(!x.isBlank() && !series.isEmpty() && series.size() <= 4, "MISSING_DIMENSIONS");
        List<String> keys = new ArrayList<>(List.of(x));
        for (var item : series) {
            String y = text(item.get("yKey"));
            require(!y.isBlank() && !keys.contains(y), "INVALID_SERIES_KEY");
            keys.add(y);
        }
        Map<String, Object> transform = map(spec.get("transform"));
        Map<String, String> units = new LinkedHashMap<>();
        var original = catalog.dataset(reference);
        var computedSource = catalog.get(reference);
        for (String key : keys.subList(1, keys.size())) {
            var policy = original == null ? null : original.metricPolicies().get(key);
            units.put(key, policy != null ? policy.unit() : computedSource == null ? ""
                : computedSource.rows().isEmpty() ? computedSource.metricUnit() : computedSource.unit());
        }
        if (!transform.isEmpty()) {
            require(complete, "INCOMPLETE_AGGREGATION_INPUT");
            var source = catalog.dataset(reference);
            var policy = source == null ? null : source.metricPolicies().get(text(transform.get("metric")));
            require(policy != null && !policy.preAggregated(), "UNKNOWN_OR_PREAGGREGATED_METRIC");
            require(policy.allowedOperations().contains(text(transform.get("operation")).toUpperCase(Locale.ROOT)),
                "AGGREGATION_NOT_ALLOWED");
            require(rows.stream().allMatch(row -> row.containsKey(text(transform.get("metric")))), "UNKNOWN_METRIC_FIELD");
            require(series.size() == 1 && "value".equals(keys.get(1))
                && x.equals(text(transform.get("groupBy"))) && !"value".equals(x), "INVALID_TRANSFORM_DIMENSIONS");
            rows = aggregate(rows, transform);
            String operation = text(transform.get("operation")).toUpperCase(Locale.ROOT);
            units.put("value", "SHARE".equals(operation) ? "%" : "COUNT".equals(operation) ? "条" : policy.unit());
        }
        require(units.values().stream().filter(unit -> !unit.isBlank()).distinct().count() <= 1, "INCOMPATIBLE_SERIES_UNITS");
        for (var item : series) if (item.containsKey("unit")) require(
            text(item.get("unit")).equals(units.get(text(item.get("yKey")))), "SERIES_UNIT_MISMATCH");
        List<Map<String, Object>> projected = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : keys) {
                require(row.containsKey(key) && row.get(key) != null, "MISSING_SOURCE_VALUE");
                values.put(key, row.get(key));
            }
            projected.add(values);
        }
        if (dataset.containsKey("rows")) {
            List<Map<String, Object>> proposed = maps(dataset.get("rows"));
            require(!proposed.isEmpty() && (transform.isEmpty() || proposed.size() == projected.size()), "EMPTY_OR_PARTIAL_DERIVED_ROWS");
            List<Map<String, Object>> available = new ArrayList<>(projected);
            List<Map<String, Object>> selected = new ArrayList<>();
            for (var row : proposed) {
                int match = -1;
                for (int i = 0; i < available.size(); i++) if (sameRow(row, available.get(i), keys)) { match = i; break; }
                require(match >= 0, "ROW_VALUE_OR_ENTITY_MISMATCH");
                selected.add(available.remove(match));
            }
            projected = selected;
        }
        require(!projected.isEmpty() && projected.size() <= 120, "EMPTY_OR_OVERSIZED_VIEW");
        for (var row : projected) for (String key : keys.subList(1, keys.size())) safeNumber(row.get(key));
        if ("ranking".equals(spec.get("intent"))) {
            require(series.size() == 1, "RANKING_ONE_MEASURE");
            projected.sort((a, b) -> number(b.get(keys.get(1))).compareTo(number(a.get(keys.get(1)))));
        }
        if ("line".equals(type)) {
            require(projected.size() >= 2, "TREND_NEEDS_DATES");
            LocalDate previous = null;
            for (var row : projected) {
                LocalDate date = LocalDate.parse(text(row.get(x)));
                require(previous == null || date.isAfter(previous), "DATES_NOT_INCREASING");
                previous = date;
            }
        }
        if ("scatter".equals(type)) for (var row : projected) safeNumber(row.get(x));
        if ("pie".equals(type)) {
            require(series.size() == 1, "PIE_ONE_MEASURE");
            BigDecimal total = BigDecimal.ZERO;
            Set<String> categories = new HashSet<>();
            for (var row : projected) {
                BigDecimal number = number(row.get(keys.get(1)));
                require(number.signum() >= 0 && categories.add(text(row.get(x))), "INVALID_PIE_PARTITION");
                total = total.add(number);
            }
            require(total.signum() > 0, "ZERO_PIE_TOTAL");
        }
        if ("kpi".equals(type)) require(projected.size() == 1 && series.size() == 1, "KPI_ONE_VALUE");
        Map<String, Object> verified = new LinkedHashMap<>();
        verified.put("schemaVersion", "visualization_spec.v2");
        verified.put("validationStatus", "VERIFIED_SOURCE_DATA");
        verified.put("type", "kpi".equals(type) ? "metric" : "chart");
        if ("kpi".equals(type)) verified.put("metrics", List.of(Map.of(
            "label", text(projected.get(0).get(x)), "value", projected.get(0).get(keys.get(1)), "unit", units.get(keys.get(1)))));
        verified.put("chartType", type);
        if ("ranking".equals(spec.get("intent"))) verified.put("orientation", "horizontal");
        verified.put("title", text(spec.get("title")));
        verified.put("scope", "仅对应本次返回记录，非业务总体；图表计算已核验，业务解释需结合正文");
        verified.put("dataset", Map.of("sourceRef", reference, "xKey", x, "columns", keys,
            "rows", projected, "series", series.stream().map(item -> Map.of(
                "name", text(item.get("name")), "yKey", text(item.get("yKey")), "unit", units.get(text(item.get("yKey"))))).toList()));
        verified.put("ui", Map.of("allowSwitch", true));
        return verified;
    }


    private Map<String, Object> compileIntent(Map<String, Object> input) {
        if (!input.containsKey("intent")) return input;
        String intent = text(input.get("intent")).toLowerCase(Locale.ROOT);
        String type = switch (intent) {
            case "trend" -> "line";
            case "ranking", "comparison" -> "bar";
            case "composition" -> "pie";
            case "metric" -> "kpi";
            case "relationship" -> "scatter";
            default -> throw new IllegalArgumentException("UNSUPPORTED_INTENT");
        };
        Map<String, Object> result = new LinkedHashMap<>(input);
        result.put("chartType", type);
        result.put("intent", intent);
        if (!input.containsKey("dataset")) result.put("dataset", Map.of("sourceRef", text(input.get("datasetRef")),
            "xKey", text(input.get("x")), "series", List.of(Map.of("yKey", text(input.get("y")), "name", text(input.get("y"))))));
        return result;
    }

    /** Rejected derived views fall back to original observations, never model-provided rows. */
    private String sourceTable(Map<String, Object> spec, VerifiedReportDataCatalog catalog) {
        String ref = text(map(spec.get("dataset")).get("sourceRef"));
        if (ref.isEmpty()) ref = text(spec.get("datasetRef"));
        var source = catalog.dataset(ref);
        List<Map<String, Object>> rows = source == null ? List.of() : source.rows();
        var computed = catalog.get(text(spec.get("dataRef")));
        if (computed != null) rows = computed.rows().isEmpty() && computed.metric() != null
            ? List.of(Map.of("entity", computed.title(), "value", computed.metric(), "unit", computed.metricUnit())) : computed.rows();
        if (rows.isEmpty()) return "";
        List<String> columns = rows.get(0).keySet().stream().limit(8).toList();
        if (columns.isEmpty()) return "";
        StringBuilder table = new StringBuilder("原始返回记录（最多展示 20 行，未执行上述图表计算）：\n\n|");
        columns.forEach(key -> table.append(cell(key)).append('|'));
        table.append("\n|"); columns.forEach(key -> table.append("---|")); table.append('\n');
        rows.stream().limit(20).forEach(row -> {
            table.append('|'); columns.forEach(key -> table.append(cell(row.get(key))).append('|')); table.append('\n');
        });
        return table.append('\n').toString();
    }
    private String cell(Object value) {
        return text(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("|", "&#124;").replace("`", "&#96;").replace("*", "&#42;")
            .replace("[", "&#91;").replace("]", "&#93;").replace("\\", "&#92;")
            .replace("\r", " ").replace("\n", " ");
    }

    private List<Map<String, Object>> aggregate(List<Map<String, Object>> rows, Map<String, Object> transform) {
        String operation = text(transform.get("operation")).toUpperCase(Locale.ROOT);
        require(Set.of("SUM", "AVG", "COUNT", "SHARE").contains(operation), "UNSUPPORTED_TRANSFORM");
        String group = text(transform.get("groupBy")), metric = text(transform.get("metric"));
        Map<Object, BigDecimal> sums = new LinkedHashMap<>();
        Map<Object, Integer> counts = new LinkedHashMap<>();
        for (var row : rows) {
            Object category = row.get(group);
            require(category != null, "MISSING_GROUP");
            BigDecimal value = "COUNT".equals(operation) ? BigDecimal.ONE : number(row.get(metric));
            if ("SHARE".equals(operation)) require(value.signum() >= 0, "NEGATIVE_SHARE_INPUT");
            sums.merge(category, value, BigDecimal::add);
            counts.merge(category, 1, Integer::sum);
        }
        BigDecimal total = sums.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if ("SHARE".equals(operation)) require(total.signum() > 0, "ZERO_DENOMINATOR");
        List<Map<String, Object>> result = new ArrayList<>();
        sums.forEach((category, value) -> {
            BigDecimal derived = switch (operation) {
                case "AVG" -> value.divide(BigDecimal.valueOf(counts.get(category)), 8, RoundingMode.HALF_UP);
                case "SHARE" -> value.multiply(BigDecimal.valueOf(100)).divide(total, 8, RoundingMode.HALF_UP);
                default -> value;
            };
            result.add(Map.of(group, category, "value", derived));
        });
        return result;
    }
    private boolean sameRow(Map<String, Object> proposed, Map<String, Object> source, List<String> keys) {
        if (!proposed.keySet().equals(new HashSet<>(keys))) return false;
        for (String key : keys) {
            Object expected = source.get(key), actual = proposed.get(key);
            if (expected instanceof Number) {
                try { if (number(expected).compareTo(number(actual)) != 0) return false; }
                catch (RuntimeException invalid) { return false; }
            } else if (!Objects.equals(expected, actual)) return false;
        }
        return true;
    }
    private BigDecimal number(Object value) {
        require(value != null && (value instanceof Number || value instanceof String), "NON_NUMERIC_VALUE");
        try {
            BigDecimal number = new BigDecimal(value.toString());
            require(number.precision() <= 40 && Math.abs((long) number.scale()) <= 20, "UNSAFE_NUMERIC_SCALE");
            return number;
        }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("NON_NUMERIC_VALUE"); }
    }
    private void safeNumber(Object value) {
        require(number(value).abs().compareTo(new BigDecimal("9007199254740991")) <= 0, "UNSAFE_CHART_PRECISION");
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        require(list.stream().allMatch(item -> item instanceof Map<?, ?>), "INVALID_ROWS");
        return list.stream().map(ReportVisualizationAudit::map).toList();
    }
}
