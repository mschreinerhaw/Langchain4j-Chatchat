package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Pure Runtime post-processing: no model, tool invocation or cross-run dataset lookup. */
public final class VisualizationPlanningNode {
    public record Result(String markdown, List<Map<String, Object>> blocks, List<Map<String, Object>> checks) {
        public long visualizationCount() { return blocks.stream().filter(block -> !"text".equals(block.get("type"))).count(); }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final VisualizationCapabilityRegistry registry;
    private final ReportVisualizationAudit audit;
    private final Set<String> admittedEvidenceRefs;
    public VisualizationPlanningNode(VisualizationCapabilityRegistry registry) { this(registry, Set.of()); }
    public VisualizationPlanningNode(VisualizationCapabilityRegistry registry, Collection<String> admittedEvidenceRefs) {
        this.registry = registry; this.audit = new ReportVisualizationAudit(registry);
        this.admittedEvidenceRefs = Set.copyOf(admittedEvidenceRefs);
    }

    public Result execute(String markdown, VerifiedReportDataCatalog catalog) {
        return execute(markdown, catalog, false);
    }
    public Result execute(String markdown, VerifiedReportDataCatalog catalog, boolean preserveRejectedText) {
        StringBuilder output = new StringBuilder();
        StringBuilder narrative = new StringBuilder();
        List<Map<String, Object>> blocks = new ArrayList<>(), checks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] lines = (markdown == null ? "" : markdown).split("(?<=\n)", -1);
        for (int i = 0; i < lines.length; i++) {
            var opening = Pattern.compile("^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)[\\r\\n]*$").matcher(lines[i]);
            if (!opening.matches()) { output.append(lines[i]); narrative.append(lines[i]); continue; }
            String marker = opening.group(1), info = opening.group(2).trim();
            Pattern closing = Pattern.compile("^ {0,3}" + Pattern.quote(marker.substring(0, 1)) + "{" + marker.length() + ",}[ \\t]*[\\r\\n]*$");
            int end = i + 1;
            while (end < lines.length && !closing.matcher(lines[end]).matches()) end++;
            Map<String, Object> wrapped = null;
            if ("json".equalsIgnoreCase(info) && end < lines.length) {
                try {
                    String body = String.join("", Arrays.copyOfRange(lines, i + 1, end));
                    if (body.length() <= 40000) {
                        Map<String, Object> wrapper = JSON.readValue(body, new TypeReference<>() {});
                        if (wrapper.containsKey("reportBlock")) wrapped = ReportBlockSchema.map(wrapper.get("reportBlock"));
                        else if (wrapper.keySet().containsAll(Set.of("chartType", "datasetRef", "encoding"))) wrapped = wrapper;
                    }
                } catch (Exception ignored) { /* Unrelated JSON remains available to the legacy auditor. */ }
            }
            if (!"json:report-block".equalsIgnoreCase(info) && wrapped == null) {
                for (; i < Math.min(end + 1, lines.length); i++) { output.append(lines[i]); narrative.append(lines[i]); }
                i--; continue;
            }
            try {
                require(end < lines.length, "INCOMPLETE_REPORT_BLOCK");
                String body = String.join("", Arrays.copyOfRange(lines, i + 1, end));
                require(body.length() <= 16000 && blocks.stream().filter(block -> !"text".equals(block.get("type"))).count() < 6, "REPORT_BLOCK_BUDGET_EXCEEDED");
                Map<String, Object> proposed = wrapped == null ? JSON.readValue(body, new TypeReference<>() {}) : wrapped;
                ReportBlockSchema.validate(registry.reportBlockSchema(), proposed);
                require(ids.add(String.valueOf(proposed.get("id"))), "DUPLICATE_REPORT_BLOCK_ID");
                Map<String, Object> verified = bind(proposed, catalog);
                appendNarrative(blocks, narrative);
                blocks.add(verified);
                output.append("```json\n").append(ModelProtocolJson.compact(Map.of("reportBlock", verified))).append("\n```\n");
                checks.add(Map.of("status", "VERIFIED", "blockId", proposed.get("id"), "datasetRef", proposed.get("datasetRef")));
            } catch (Exception rejected) {
                if (preserveRejectedText) {
                    // Deny executable artifact binding, preserve the model's payload as inert text.
                    String openingText = marker + "text\n";
                    output.append(openingText); narrative.append(openingText);
                    for (int line = i + 1; line < Math.min(end + 1, lines.length); line++) {
                        output.append(lines[line]); narrative.append(lines[line]);
                    }
                }
                if (checks.size() < 12) checks.add(Map.of("status", "REJECTED", "reason",
                    rejected instanceof IllegalArgumentException ? String.valueOf(rejected.getMessage()) : "INVALID_REPORT_BLOCK_JSON"));
            }
            i = end;
        }
        // Retain support for already deployed v2 reports, using the same data binding verifier.
        var legacy = audit.audit(output.toString(), catalog, preserveRejectedText);
        appendNarrative(blocks, narrative);
        checks.addAll(legacy.checks());
        return new Result(legacy.markdown(), List.copyOf(blocks), List.copyOf(checks));
    }
    private static void appendNarrative(List<Map<String, Object>> blocks, StringBuilder narrative) {
        if (!narrative.toString().isBlank()) blocks.add(Map.of("schemaVersion", "report_block.v1",
            "id", "narrative-" + blocks.size(), "type", "text", "markdown", narrative.toString(),
            "validationStatus", "MODEL_AUTHORED"));
        narrative.setLength(0);
    }

    private Map<String, Object> bind(Map<String, Object> proposed, VerifiedReportDataCatalog catalog) {
        String type = String.valueOf(proposed.get("chartType")), reference = String.valueOf(proposed.get("datasetRef"));
        require(registry.supports(type), "UNAUTHORIZED_OR_UNREGISTERED_CHART");
        String blockType = String.valueOf(proposed.get("type"));
        require(blockType.equals(Set.of("table", "metric").contains(type) ? type : "chart"), "REPORT_BLOCK_TYPE_MISMATCH");
        var encoding = ReportBlockSchema.map(proposed.get("encoding"));
        Map<String, Object> visualization;
        if ("table".equals(type)) visualization = table(reference, encoding, proposed, catalog);
        else {
            List<String> y = fields(encoding.get("y"));
            String x = String.valueOf(encoding.getOrDefault("x", ""));
            require(!x.isBlank() && !y.isEmpty(), "MISSING_DIMENSIONS");
            require(!encoding.containsKey("columns"), "INVALID_CHART_ENCODING");
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("chartType", registry.rendererType(type));
            spec.put("title", proposed.get("title")); spec.put("reason", proposed.get("reason"));
            var computed = catalog.get(reference);
            if ("pie".equals(type)) require(computed == null && catalog.dataset(reference) != null
                && catalog.dataset(reference).complete(), "INCOMPLETE_PIE_PARTITION");
            if (computed != null) spec.put("dataRef", reference);
            spec.put("dataset", Map.of("sourceRef", reference, "xKey", x,
                "series", y.stream().map(key -> Map.of("name", key, "yKey", key)).toList()));
            visualization = audit.verify(spec, catalog);
        }
        Set<String> knownRefs = new HashSet<>(catalog.datasetReferences());
        knownRefs.addAll(admittedEvidenceRefs);
        knownRefs.add(reference);
        if (catalog.get(reference) != null) knownRefs.addAll(catalog.get(reference).recordRefs());
        require(fields(proposed.get("evidenceRefs")).stream().allMatch(knownRefs::contains), "UNKNOWN_VISUALIZATION_EVIDENCE_REF");
        Map<String, Object> block = new LinkedHashMap<>(proposed);
        block.put("schemaVersion", "report_block.v1");
        block.put("validationStatus", "VERIFIED_SOURCE_DATA");
        Map<String, Object> ui = new LinkedHashMap<>(ReportBlockSchema.map(visualization.get("ui")));
        ui.put("allowedChartTypes", registry.types().stream().map(registry::rendererType).toList());
        Map<String, Object> rendered = new LinkedHashMap<>(visualization); rendered.put("ui", ui);
        block.put("visualizationSpec", rendered);
        block.put("dataBinding", Map.of("datasetRef", reference, "scope", "THIS_RUN_ONLY"));
        return Collections.unmodifiableMap(block);
    }

    private Map<String, Object> table(String reference, Map<String, Object> encoding,
        Map<String, Object> proposed, VerifiedReportDataCatalog catalog) {
        var source = catalog.dataset(reference); var computed = catalog.get(reference);
        require(source != null || computed != null, "UNKNOWN_DATA_REF");
        List<Map<String, Object>> rows = source != null ? source.rows() : computed.rows().isEmpty()
            ? List.of(Map.of("entity", computed.title(), "value", computed.metric())) : computed.rows();
        List<String> columns = fields(encoding.get("columns"));
        require(!columns.isEmpty() && !encoding.containsKey("x") && !encoding.containsKey("y"), "INVALID_TABLE_ENCODING");
        require(!rows.isEmpty() && rows.size() <= 120, "EMPTY_OR_OVERSIZED_VIEW");
        List<Map<String, Object>> selected = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> projection = new LinkedHashMap<>();
            for (String key : columns) { require(row.containsKey(key), "UNKNOWN_FIELD"); projection.put(key, row.get(key)); }
            selected.add(projection);
        }
        return Map.of("schemaVersion", "visualization_spec.v2", "validationStatus", "VERIFIED_SOURCE_DATA",
            "type", "table", "chartType", "table", "title", proposed.get("title"),
            "dataset", Map.of("sourceRef", reference, "columns", columns, "rows", selected),
            "ui", Map.of("defaultView", "table"));
    }
    private static List<String> fields(Object value) {
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
