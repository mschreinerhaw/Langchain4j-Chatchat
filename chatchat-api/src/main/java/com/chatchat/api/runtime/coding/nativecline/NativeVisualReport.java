package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.evidence.VisualReportArtifacts;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Presentation projection of admitted Native files, never a report generator or business verifier. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public final class NativeVisualReport {
    private final UserAssetService assets;private final ObjectMapper json;
    public NativeVisualReport(UserAssetService assets,ObjectMapper json){this.assets=assets;this.json=json;}
    public static String instruction(Map<String,Object> context){
        if(!VisualReportMode.active(context))return "";
        return "\nThe user enabled interactive HTML report delivery. Deliver exactly one self-contained HTML entry as REPORT (choose its name and directory), plus all local JS/CSS/data/image/font dependencies as AUXILIARY or SOURCE in delivery_manifest.json. The HTML is displayed directly in an opaque sandbox iframe. Use classic scripts (bundle modules first), relative local resource paths, inline scripts/styles or delivered files; no CDN, network requests, external embeds, forms, service workers, nested frames or navigation. Local fetch of delivered JSON/CSV/TXT resources is supported. Do not use dynamic module imports, web workers, absolute workspace URLs or browser authentication. Make the report responsive, readable and interactive where useful; include narrative, source attribution, limitations and actual results inside the HTML. Keep structured RESULT JSON and computation evidence. Open/check actual HTML and JS, exercise interactions and fix errors before delivery. Markdown may be an optional companion, but cannot replace the HTML entry. When charts improve the actual analysis, generate real PNG/SVG or Plotly JSON files using your native tools and deliver them as CHART artifacts. Explain results inside the HTML and reference the actual local chart files. Choose source names, plots and structure yourself. With insufficient or non-numerical evidence, explain the gap instead of inventing charts. Do not fetch the same inputs again only for presentation. Optional charts are not mandatory for ordinary questions or empty data. Honor these presentation preferences (false autoCharts/autoImages disables optional plots/illustrations): "+ReportPresentation.read(context)+". Declared visualization scene: "+ReportPresentation.read(context).getOrDefault("scenario","unspecified")+". Selected style profile: "+ReportPresentation.read(context).getOrDefault("styleProfile","auto")+". The named profile is an output requirement, not a decorative hint. Resolve its definition from matching authorized Skill instructions and reference files before assigning semantic colors. Do not guess an intuitive positive/negative palette or silently replace the selected convention. If its definition is unavailable, use neutral colors and disclose the unresolved convention; describe the selected color convention and its actual data meaning. Apply the profile while generating PNG/SVG/Plotly; a browser cannot recolor a raster artifact. Styling does not authorize tools, financial interpretation or new data. The host registers delivered files for the authenticated report viewer; never use invented URLs or local paths as browser download URLs.\n";
    }
    public Optional<Map<String,Object>> project(KernelDataScope scope,String task,Map<String,Object> context,List<?> rows,List<UserAssetService.File> files){
        if(!VisualReportMode.active(context))return Optional.empty();
        var admitted=files.stream().filter(f->"ARTIFACT".equals(f.kind())&&task.equals(f.taskId())&&Objects.equals(scope.runId(),f.runId())).toList();
        Map<?,?> row=rows.size()==1&&rows.get(0) instanceof Map<?,?> value?value:Map.of();
        Map<?,?> raw=row.get("visualReport") instanceof Map<?,?> value?value:Map.of();
        String markdown=Objects.toString(row.get("reportMarkdown"),"");
        if(markdown.isBlank())for(var file:admitted)if(file.fileName().toLowerCase(Locale.ROOT).endsWith(".md")&&file.size()<=512000){markdown=new String(assets.readTask(scope,task,file.id()),StandardCharsets.UTF_8);break;}
        var html=admitted.stream().filter(f->f.fileName().toLowerCase(Locale.ROOT).endsWith(".html")).toList();
        if(html.size()>1)throw new IllegalArgumentException("Exactly one HTML report entry required");
        if(html.size()==1){
            var entry=html.get(0);NativeHtmlReport.path(entry);
            if(entry.size()>8L*1024*1024)throw new IllegalArgumentException("HTML entry exceeds presentation budget");
            String content=new String(assets.readTask(scope,task,entry.id()),StandardCharsets.UTF_8);
            var pageTitle=Pattern.compile("(?is)<title(?:\\s[^>]*)?>([^<]{1,240})</title>").matcher(content);
            String htmlTitle=pageTitle.find()?org.springframework.web.util.HtmlUtils.htmlUnescape(pageTitle.group(1).trim()):Objects.toString(raw.get("title"),entry.fileName());
            var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",VisualReportMode.SCHEMA);result.put("reportMode","visual");result.put("status","SUCCESS");result.put("title",htmlTitle);result.put("summary","");result.put("markdown",markdown);result.put("htmlReport",NativeHtmlReport.descriptor(entry));
            result.put("presentation",ReportPresentation.read(context));for(String key:List.of("charts","tables","metrics","sections","limitations","dataSources"))result.put(key,List.of());result.put("validation",Map.of("checks",List.of("TASK_OBJECT_INTEGRITY"),"warnings",List.of()));result.put("validationScope","STRUCTURE_AND_OBJECT_INTEGRITY");result.put("businessValidationPassed",false);return Optional.of(result);
        }
        if(markdown.isBlank())return Optional.empty();
        var charts=new ArrayList<Map<String,Object>>();var warnings=new ArrayList<String>();
        for(var file:admitted){
            Map<?,?> declared=Map.of();
            if(raw.get("charts") instanceof List<?> descriptors)for(var candidate:descriptors)if(candidate instanceof Map<?,?> d&&d.get("fileName") instanceof String path&& !path.isBlank() &&(path.equals(file.fileName())||file.sourceRef()!=null&&file.sourceRef().endsWith(":"+path))){declared=d;break;}
            String name=file.fileName().toLowerCase(Locale.ROOT);String kind=name.endsWith(".png")?"png":name.endsWith(".svg")?"svg":name.endsWith(".json")&&"plotly".equals(declared.get("kind"))?"plotly":null;
            if(kind==null)continue;
            String id="native-chart-"+(charts.size()+1),title=Objects.toString(declared.get("title"),file.fileName());
            try{
                if(file.size()>8L*1024*1024)throw new IllegalArgumentException("Chart exceeds presentation budget");
                byte[] bytes=assets.readTask(scope,task,file.id());
                if(kind.equals("png"))VisualReportArtifacts.validatePng(bytes);else if(kind.equals("svg"))VisualReportArtifacts.validateSvg(bytes);else VisualReportArtifacts.validatePlotly(json.readValue(bytes,Object.class));
                charts.add(Map.of("id",id,"title",title,"kind",kind,"caption",Objects.toString(declared.get("caption"),""),"artifact",Map.of("fileId",file.id(),"fileName",file.fileName(),"sha256",file.sha256(),"size",file.size(),"mediaType",kind.equals("png")?"image/png":kind.equals("svg")?"image/svg+xml":"application/json")));
            }catch(Exception invalid){charts.add(Map.of("id",id,"title",title,"kind","unavailable","renderError","CHART_ARTIFACT_REJECTED"));warnings.add("图表文件未通过格式或完整性校验："+file.fileName());}
        }
        if(charts.isEmpty()&&raw.isEmpty())return Optional.empty();
        var title=Pattern.compile("(?m)^#{1,6}\\s+(.{1,240})$").matcher(markdown);
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",VisualReportMode.SCHEMA);report.put("reportMode","visual");report.put("title",title.find()?title.group(1):"图文报告");report.put("summary","");report.put("markdown",markdown);
        report.put("status",warnings.isEmpty()?"SUCCESS":"PARTIAL");report.put("presentation",ReportPresentation.read(context));report.put("metrics",List.of());report.put("tables",List.of());report.put("charts",charts);
        report.put("sections",List.of(Map.of("id","native-report-body","title","报告正文","narrative",markdown,"chartIds",charts.stream().map(c->c.get("id")).toList(),"tableIds",List.of())));
        report.put("dataSources",row.get("evidenceReferences") instanceof List<?> refs?refs:List.of());report.put("limitations",row.get("limitations") instanceof List<?> values?values:List.of());report.put("validation",Map.of("checks",List.of(),"warnings",warnings));report.put("validationScope","STRUCTURE_AND_OBJECT_INTEGRITY");report.put("businessValidationPassed",false);
        return Optional.of(report);
    }
}
