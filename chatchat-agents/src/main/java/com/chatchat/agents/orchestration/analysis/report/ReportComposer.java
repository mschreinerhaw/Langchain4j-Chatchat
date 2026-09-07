package com.chatchat.agents.orchestration.analysis.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Enforces data/claim lineage before promoting a finding into the report's executive conclusions. */
public final class ReportComposer {
    public static final String VERSION = "analytical_report.v1";
    private final VisualizationPlanningContract planner = new VisualizationPlanningContract();
    private final ChartDataExecutor executor = new ChartDataExecutor();

    /** Optional visual enrichment is audited independently from model-authored business prose. */
    public ReportVisualizationAudit.Result auditVisualizations(String markdown, VerifiedReportDataCatalog catalog) {
        return new ReportVisualizationAudit().audit(markdown, catalog);
    }

    public AnalyticalInsightBlock compose(String id, String section, String question, String observation,
        String interpretation, String implication, String confidence, List<String> caveats,
        List<Map<String, Object>> evidence, String dataRef, String intent, VerifiedReportDataCatalog catalog) {
        var data = catalog.get(dataRef);
        List<String> refs = evidence.stream().flatMap(item -> strings(item.get("recordRefs")).stream()).toList();
        boolean dataBound = data != null && data.recordRefs().stream().allMatch(ref -> refs.stream()
            .anyMatch(claimRef -> ref.equals(claimRef) || ref.startsWith(claimRef + ".")));
        boolean rejected = evidence.stream().anyMatch(item ->
            "REJECTED".equals(String.valueOf(item.get("status"))));
        boolean reviewRequired = evidence.stream().anyMatch(item ->
            !strings(item.get("reviewReasons")).isEmpty()
                || List.of("REVIEW_REQUIRED", "DOWNGRADE").contains(String.valueOf(item.get("status"))));
        boolean evidenceBound = evidence.stream().anyMatch(item ->
            !strings(item.get("recordRefs")).isEmpty()
                && !strings(item.get("supportingValues")).isEmpty());
        boolean publishableEvidence = (dataBound || evidenceBound) && !rejected;
        List<String> limitations = new ArrayList<>(caveats);
        if (!publishableEvidence) limitations.add("未绑定可验证的记录证据或计算数据；保留为待验证说明，不进入核心业务结论。");
        if (reviewRequired) limitations.add("该判断包含待复核项，以受限结论发布，需结合证据范围理解。");
        if (rejected) limitations.add("该判断的依据已被拒绝，不作为业务结论发布。");
        var plan = dataBound ? planner.plan(id, data, intent) : null;
        if (dataBound && plan == null && !data.rows().isEmpty()) {
            limitations.add("当前数据或分析意图不满足图表条件，保留数据表供核对。");
        }
        List<Map<String, Object>> rows = dataBound ? executor.execute(data, plan) : List.of();
        Map<String, Object> visualization = plan == null ? Map.of() : planner.render(plan, rows);
        Map<String, Object> blockData = new java.util.LinkedHashMap<>(dataBound ? data.toMap() : Map.of());
        if (dataBound) blockData.put("rows", rows);
        return new AnalyticalInsightBlock(id, section, question, observation, interpretation, implication,
            confidence, List.copyOf(limitations), List.copyOf(evidence), blockData, visualization,
            new AnalyticalInsightBlock.PresentationStrategy(!publishableEvidence ? "DATA_STATUS"
                : !dataBound ? "TEXT"
                : plan != null ? "CHART" : rows.isEmpty() ? "KPI" : "TABLE",
                dataBound && !rows.isEmpty(), dataBound && data.metric() != null,
                publishableEvidence && "CORE".equals(section),
                !publishableEvidence ? "INSUFFICIENT_DATA"
                    : reviewRequired ? dataBound ? "LIMITED_DATA_BOUND" : "LIMITED_EVIDENCE_BOUND"
                    : dataBound ? "VERIFIED_DATA_BOUND" : "VERIFIED_EVIDENCE_BOUND"));
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** Text-only clients receive a projection of the same blocks, including the same data and caveats. */
    public String markdown(String question, List<AnalyticalInsightBlock> blocks) {
        StringBuilder output = new StringBuilder("# 数据分析报告\n\n分析问题：").append(question).append("\n\n## 核心业务判断\n\n");
        List<AnalyticalInsightBlock> primary = blocks.stream()
            .filter(block -> block.presentation().primaryConclusion()).limit(5).toList();
        if (primary.isEmpty()) {
            primary = blocks.stream().filter(block -> block.presentation().validationStatus().startsWith("VERIFIED_"))
                .filter(block -> !List.of("ACTION", "REVIEW").contains(block.section())).limit(5).toList();
        }
        if (primary.isEmpty()) {
            primary = blocks.stream().filter(block -> block.presentation().validationStatus().startsWith("LIMITED_"))
                .filter(block -> !List.of("ACTION", "REVIEW").contains(block.section())).limit(3).toList();
        }
        if (primary.isEmpty()) output.append("当前没有已绑定可验证证据的业务判断；下文仅展示数据状态与未决事项。\n\n");
        primary.stream().map(block -> headline(block.observation()))
            .filter(headline -> !headline.isBlank()).distinct()
            .forEach(headline -> output.append("- ").append(headline).append("\n\n"));
        int findingIndex = 0;
        java.util.Set<String> renderedCaveats = new java.util.LinkedHashSet<>();
        for (var block : blocks) {
            output.append("## ").append(block.question().isBlank() ? "分析发现 " + (++findingIndex) : block.question()).append("\n\n");
            if ("DATA_STATUS".equals(block.presentation().primaryPresentation())) output.append("数据状态：待补充可验证数据。\n\n");
            output.append(block.observation()).append("\n\n");
            if (block.presentation().showKeyMetrics()) output.append("关键数据：").append(block.data().get("title"))
                .append(" = ").append(block.data().get("metric")).append(" ").append(block.data().get("metricUnit")).append("\n\n");
            if (block.presentation().showDataTable() && block.data().get("rows") instanceof List<?> rows) {
                output.append("| 对象 | 数值（").append(cell(block.data().get("unit"))).append("） |\n| --- | ---: |\n");
                for (Object raw : rows) if (raw instanceof Map<?, ?> row) output.append("| ")
                    .append(cell(row.get("entity"))).append(" | ").append(cell(row.get("value"))).append(" |\n");
                output.append('\n');
            }
            if (!block.interpretation().isBlank()) appendDetail(output, "解释与判断", block.interpretation());
            if (!block.implication().isBlank()) appendDetail(output, "业务含义", block.implication());
            if (!block.confidence().isBlank()) output.append("判断可信度：")
                .append(confidenceLabel(block.confidence())).append("\n\n");
            block.caveats().stream().filter(renderedCaveats::add)
                .forEach(caveat -> output.append("- 限制：").append(caveat).append('\n'));
            List<String> sources = block.evidence().stream().map(item -> item.get("sourceScope"))
                .filter(java.util.Objects::nonNull).map(String::valueOf).map(String::trim)
                .filter(source -> !source.isBlank() && !"null".equalsIgnoreCase(source)).distinct().toList();
            if (!sources.isEmpty()) output.append("\n数据来源：").append(String.join("、", sources)).append('\n');
            output.append('\n');
        }
        return output.toString().trim();
    }

    /** The executive summary quotes only each conclusion's lead sentence; details stay in its section. */
    private String headline(String observation) {
        String text = observation == null ? "" : observation.trim();
        return text.split("(?<=[。！？!?])|(?<=\\.)\\s+", 2)[0].trim();
    }

    /** Multi-line detail fields (e.g. 比较基准/比较结果 scaffolding) render as a labeled list, not one blob. */
    private void appendDetail(StringBuilder output, String label, String value) {
        List<String> lines = value.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        if (lines.size() <= 1) {
            output.append(label).append("：").append(lines.isEmpty() ? "" : lines.get(0)).append("\n\n");
            return;
        }
        output.append(label).append("：\n\n");
        lines.forEach(line -> output.append("- ").append(line).append('\n'));
        output.append('\n');
    }

    private String confidenceLabel(String confidence) {
        return switch (confidence.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            case "LOW" -> "低";
            default -> confidence;
        };
    }

    private String cell(Object value) {
        return String.valueOf(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
