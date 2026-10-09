package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.protocol.ModelProtocolJson;
import java.util.*;

/** Adds declarative planning to the existing synthesis call, without source rows or renderer code. */
public final class VisualizationCapabilityInjector {
    private final VisualizationCapabilityRegistry registry;
    public VisualizationCapabilityInjector(VisualizationCapabilityRegistry registry) { this.registry = registry; }

    public Map<String, Object> snapshot(VerifiedReportDataCatalog catalog) {
        var estimator = new com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator();
        List<Map<String, Object>> datasets = new ArrayList<>();
        long tokens = 0;
        for (var dataset : catalog.visualizationMetadataView()) {
            long size = estimator.estimate(dataset).tokens();
            if (tokens + size > 4000) continue;
            datasets.add(dataset); tokens += size;
        }
        return Map.of("schemaVersion", "visualization_capabilities.v1", "capabilities", registry.describe(),
            "reportBlockSchema", registry.reportBlockSchema(),
            "allowedChartTypes", registry.types(), "datasets", datasets);
    }

    public String inject(String prompt, VerifiedReportDataCatalog catalog) {
        return prompt + "\n\nVisualizationCapabilityInjector: " + ModelProtocolJson.compact(snapshot(catalog)) + """

            In this same synthesis response, write the full narrative analysis and optionally place a
            json:report-block fenced JSON object next to each conclusion that benefits from a graphic.
            Follow the supplied ReportBlock schema and allowedChartTypes. Choose whether to visualize,
            the best available type, and explain the conclusion supported and why the view helps.
            Reference an existing datasetRef and its exact field names. Prefer verified computed findings.
            Never invent dataset IDs, fields, rows or values. Source metadata is not an analysis conclusion.
            No JavaScript, HTML, SQL or renderer options. Runtime binds actual values and validates the plan.
            Use at most six blocks. If no view supports the conclusion, retain prose or a verified table.
            Unsupported, unauthorized or invalid views are omitted without changing the narrative.
            Do not request another model call or repeat data collection for visualization planning.
            """;
    }
    public String injectReportDraft(String prompt, VerifiedReportDataCatalog catalog) {
        return inject(prompt, catalog) + "\nPreserve this call's outer analysis JSON schema. Put the narrative and optional json:report-block fences inside the reportMarkdown string, never outside the outer response object.";
    }
}
