package com.chatchat.common.runtime.coding;

import java.util.Map;

/** Request-scoped presentation preference. It grants no execution or data authority. */
public final class VisualReportMode {
    private VisualReportMode() { }
    public static String read(Map<String,?> attributes) {
        Object value=attributes==null?null:attributes.get("reportMode");
        if(value==null)return "off";
        if(!"off".equals(value)&&!"visual".equals(value))throw new IllegalArgumentException("reportMode must be off or visual");
        return (String)value;
    }
    public static boolean active(Map<String,?> attributes){return "visual".equals(read(attributes));}
    public static boolean analysisRequested(Map<String,?> attributes){return active(attributes)
        && ("DATA_ANALYSIS".equals(attributes.get("taskType"))||"DATA_ANALYSIS".equals(attributes.get("workflowFamily")));}
    public static final String SCHEMA="python_visual_report.v1";
    public static final String INSTRUCTION="""
        Visual report mode is enabled for THIS analysis only. Reuse the delivered immutable inputs and the calculations you already perform; never fetch the same data again for charts.
        In the single rows.jsonl report row ALSO write visualReport, an object with schemaVersion='python_visual_report.v1', title, summary,
        status (SUCCESS, PARTIAL, NO_DATA or FAILED), metrics, sections, charts, tables, dataSources and validation.
        Choose methods, chart types and sections dynamically for the actual question. No fixed business report template.
        metrics: [{id,label,value:string,unit,calculation}]; preserve exact computed decimal values as strings.
        Use the user's language for report title, summary, narrative, metric labels and table headings. Units must come from explicit source metadata or user instructions; leave unit empty when unspecified, never assume currency, scale or business meaning from a field name.
        tables: [{id,title,columns:[string],rows:[[scalar]],scope}]; use bounded summary tables, not the complete input dataset.
        charts: [{id,title,kind:'png'|'svg'|'plotly',fileName,caption}]. Write each file directly in /workspace/output/results with a safe basename.
        Use installed Matplotlib for static PNG/SVG and Plotly fig.write_json for interactive plots (use ordinary JSON arrays, not binary/base64 ndarray transport; set template=None to omit unused default map/image templates).
        Do not generate HTML or JavaScript. Plotly figures must be offline: no external URLs, image/map layers, custom scripts or credentials.
        sections: [{title,narrative,chartIds:[string],tableIds:[string]}]; narrative and summary must describe the same computed values, units, scope and missing-data treatment.
        dataSources: [exact delivered dataset references actually used]. validation: {checks:[{name,status:'PASSED'|'FAILED',detail}],warnings:[string]}.
        Include timings:{analysisMs:number,chartMs:number} measured with time.perf_counter; never invent unavailable timings.
        Build every metric, table, plot and narrative from the SAME computed Python objects; assert consistency before writing results.
        Do not invent absent values or claim statistical/business validation that was not performed. Record empty inputs and dependency/plot failures honestly.
        If a chart fails, keep valid textual/computed results, omit the broken chart, set PARTIAL and include the actual failure in warnings; do not fail the whole report unnecessarily.
        reportMarkdown remains the complete authored text fallback. Never put local absolute paths or raw source collections into user-facing text.
        User-facing title, summary, sections, captions and reportMarkdown must focus on analysis findings, supporting business data, implications and recommendations.
        Keep code, Python/library names, execution steps, task IDs, file references, hashes and technical validation receipts out of user-facing prose; retain these only in internal evidence and validation fields.
        Explain material data gaps, sample limits, uncertainty and business calculation assumptions in the narrative, using plain language. Never hide a limitation that affects a conclusion.
        Generate rows and all charts in the SAME program, then use artifact_read to inspect visualReportValidation and repair technical artifact failures before finishing. Preserve partial output if a failure cannot be repaired within the existing permissions and budget.
        These output requirements do not change permissions, resource limits, task classification or controlled asset-template rules.
        """;
}
