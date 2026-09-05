package com.chatchat.agents.orchestration.analysis.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Enforces data/claim lineage before promoting a finding into the report's executive conclusions. */
public final class ReportComposer {
    public static final String VERSION = "analytical_report.v1";
    private final VisualizationPlanningContract planner = new VisualizationPlanningContract();
    private final ChartDataExecutor executor = new ChartDataExecutor();

    public AnalyticalInsightBlock compose(String id, String section, String question, String observation,
        String interpretation, String implication, String confidence, List<String> caveats,
        List<Map<String, Object>> evidence, String dataRef, String intent, VerifiedReportDataCatalog catalog) {
        var data = catalog.get(dataRef);
        List<String> refs = evidence.stream().flatMap(item -> strings(item.get("recordRefs")).stream()).toList();
        boolean bound = data != null && data.recordRefs().stream().allMatch(ref -> refs.stream()
            .anyMatch(claimRef -> ref.equals(claimRef) || ref.startsWith(claimRef + ".")));
        boolean reviewRequired = evidence.stream().anyMatch(item ->
            !strings(item.get("reviewReasons")).isEmpty()
                || List.of("REVIEW_REQUIRED", "DOWNGRADE", "REJECTED").contains(String.valueOf(item.get("status"))));
        List<String> limitations = new ArrayList<>(caveats);
        if (!bound) limitations.add("未绑定可验证的计算数据；保留为待验证说明，不进入核心业务结论。");
        if (reviewRequired) limitations.add("依据包含待复核判断，不进入核心业务结论。");
        var plan = bound ? planner.plan(id, data, intent) : null;
        if (bound && plan == null && !data.rows().isEmpty()) {
            limitations.add("当前数据或分析意图不满足图表条件，保留数据表供核对。");
        }
        List<Map<String, Object>> rows = bound ? executor.execute(data, plan) : List.of();
        Map<String, Object> visualization = plan == null ? Map.of() : planner.render(plan, rows);
        Map<String, Object> blockData = new java.util.LinkedHashMap<>(bound ? data.toMap() : Map.of());
        if (bound) blockData.put("rows", rows);
        return new AnalyticalInsightBlock(id, section, question, observation, interpretation, implication,
            confidence, List.copyOf(limitations), List.copyOf(evidence), blockData, visualization,
            new AnalyticalInsightBlock.PresentationStrategy(!bound ? "DATA_STATUS"
                : plan != null ? "CHART" : rows.isEmpty() ? "KPI" : "TABLE",
                bound && !rows.isEmpty(), bound && data.metric() != null,
                bound && !reviewRequired && "CORE".equals(section), bound ? "VERIFIED_DATA_BOUND" : "INSUFFICIENT_DATA"));
    }

    private List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** Text-only clients receive a projection of the same blocks, including the same data and caveats. */
    public String markdown(String question, List<AnalyticalInsightBlock> blocks) {
        StringBuilder output = new StringBuilder("# 数据分析报告\n\n分析问题：").append(question).append("\n\n## 核心业务判断\n\n");
        List<AnalyticalInsightBlock> primary = blocks.stream().filter(block -> block.presentation().primaryConclusion()).limit(5).toList();
        if (primary.isEmpty()) output.append("暂无同时绑定计算数据与证据的核心结论。\n\n");
        primary.forEach(block -> output.append("- ").append(block.observation()).append("\n\n"));
        for (var block : blocks) {
            output.append("## ").append(block.question().isBlank() ? "分析发现" : block.question()).append("\n\n");
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
            if (!block.interpretation().isBlank()) output.append("解释与判断：").append(block.interpretation()).append("\n\n");
            if (!block.implication().isBlank()) output.append("业务含义：").append(block.implication()).append("\n\n");
            if (!block.confidence().isBlank()) output.append("判断可信度：").append(block.confidence()).append("\n\n");
            block.caveats().forEach(caveat -> output.append("- 限制：").append(caveat).append('\n'));
            block.evidence().stream().map(item -> String.valueOf(item.getOrDefault("sourceScope", "")))
                .filter(source -> !source.isBlank()).distinct().forEach(source -> output.append("\n数据来源：").append(source).append('\n'));
            output.append('\n');
        }
        return output.toString().trim();
    }

    private String cell(Object value) {
        return String.valueOf(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
