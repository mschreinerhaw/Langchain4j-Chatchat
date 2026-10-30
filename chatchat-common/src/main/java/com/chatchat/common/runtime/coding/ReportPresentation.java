package com.chatchat.common.runtime.coding;

import java.util.*;

/** Presentation only. No data, tools, model identity or executable rendering configuration. */
public final class ReportPresentation {
    private ReportPresentation() { }
    public static Map<String,Object> read(Map<String,?> attributes) {
        Object raw=attributes==null?null:attributes.get("reportPresentation");
        if(raw==null)return defaults();
        if(!(raw instanceof Map<?,?> map))throw new IllegalArgumentException("reportPresentation must be an object");
        if(!Set.of("strategy","template","density","autoCharts","autoImages").containsAll(map.keySet()))
            throw new IllegalArgumentException("Unknown report presentation option");
        var result=new LinkedHashMap<String,Object>(defaults());
        map.forEach((key,value)->result.put((String)key,value));
        choice(result,"strategy",Set.of("auto","template","concise"));
        choice(result,"template",Set.of("briefing","evidence-first"));
        choice(result,"density",Set.of("comfortable","compact"));
        for(String key:List.of("autoCharts","autoImages"))if(!(result.get(key) instanceof Boolean))
            throw new IllegalArgumentException(key+" must be boolean");
        return Collections.unmodifiableMap(result);
    }
    private static void choice(Map<String,Object> value,String key,Set<String> choices){
        if(!(value.get(key) instanceof String choice)||!choices.contains(choice))throw new IllegalArgumentException("Invalid report "+key);
    }
    public static Map<String,Object> defaults(){return Map.of("strategy","auto","template","briefing",
        "density","comfortable","autoCharts",true,"autoImages",true);}
    public static String instruction(Map<String,?> attributes) {
        return """

            Report presentation protocol (additive to python_visual_report.v1):
            Persist presentation=%s in visualReport. Give each section a unique safe id.
            In the SAME report/program, author layout:{schemaVersion:'report_layout.v1',blocks:[{id,type,ref?,span?,emphasis?}]}.
            Allowed block types ONLY: summary, metric, section, chart, table, limitations, sources.
            metric/section/chart/table ref MUST name an existing element id in the matching array. Sections MUST have explicit safe IDs; a section title is display text, never its reference. Never put inline data, HTML, JavaScript, CSS, component names or external URLs in layout.
            span is full or half; emphasis normal or important. IDs are [A-Za-z0-9_-], maximum 64 characters. Include each content reference once; never invent an element to fill a slot.
            auto: choose chapter order and component count from the evidence; prefer important conclusions and anomalies first, with their supporting evidence nearby. No fixed four-metric dashboard and no mandatory chapter outline.
            Avoid repeating metric values across summary and every section; use narrative to explain meaning, scope and consequences rather than restating cards and table cells.
            template: follow the selected briefing (conclusion first) or evidence-first arrangement, using only sections and components actually supported. concise: compact narrative with necessary evidence, risks and sources retained.
            Prefer trend plots for actual time series, comparison plots for multiple objects, share/distribution plots only when a genuine whole/denominator exists, and tables for large details. With insufficient data use explanatory text, not invented charts.
            Honor autoCharts=false by omitting optional data plots; autoImages=false omits illustrations. Do not suppress necessary tables, sources or limitations. Illustrations, if useful, must be actual authorized archived PNG/SVG artifacts, marked role='illustration'; never retrieve arbitrary remote images or invent quantitative evidence.
            Every metric/chart/table in a new layout MUST carry dataRefs:[exact authorized dataset references] so values and visuals can be traced. Metric value remains the exact raw decimal string, not a rounded display replacement. Charts may declare chartType (line,bar,pie,scatter,histogram,box) describing the actual plot artifact.
            Layout does not authorize new queries or another analysis pass. Reuse computed objects and immutable results. Do not rerun data acquisition or Python just to rearrange blocks. If optional layout is malformed, preserve the complete Markdown, numbers and artifacts for compatible rendering.
            """.formatted(json(read(attributes)));
    }
    private static String json(Map<String,Object> value){
        return "{\"strategy\":\""+value.get("strategy")+"\",\"template\":\""+value.get("template")
            +"\",\"density\":\""+value.get("density")+"\",\"autoCharts\":"+value.get("autoCharts")
            +",\"autoImages\":"+value.get("autoImages")+"}";
    }
}
