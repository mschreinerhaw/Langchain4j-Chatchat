package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.assets.RuntimeAssetArchive;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.VisualReportMode;
import com.chatchat.common.runtime.coding.ReportPresentation;
import com.chatchat.common.runtime.coding.ReportLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;

/** Admits only files from the already hash-verified, stopped Python execution manifest. */
@Component
public final class VisualReportArtifacts {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(VisualReportArtifacts.class);
    private final ObjectMapper json;
    private RuntimeAssetArchive archive;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setArchive(RuntimeAssetArchive archive){this.archive=archive;}
    public VisualReportArtifacts(ObjectMapper json){this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}

    public Map<String,Object> collect(KernelDataScope scope,String task,String operation,List<Map<String,Object>> rows,
                                     Map<String,byte[]> files,Map<String,Object> execution) {
        var warnings=new ArrayList<String>();
        long started=System.nanoTime();
        try {
            require(rows.size()==1 && rows.get(0).get("visualReport") instanceof Map<?,?>,"Missing Python visual report");
            var raw=map(rows.get(0).get("visualReport"));
            require(VisualReportMode.SCHEMA.equals(raw.get("schemaVersion")),"Unknown visual report schema");
            var result=new LinkedHashMap<String,Object>();
            result.put("schemaVersion",VisualReportMode.SCHEMA);result.put("reportMode","visual");
            result.put("title",text(raw.get("title"),240));result.put("summary",text(raw.get("summary"),8000));
            result.put("markdown",text(rows.get(0).get("reportMarkdown"),200000));
            String status=text(raw.get("status"),32);
            require(Set.of("SUCCESS","PARTIAL","NO_DATA","FAILED").contains(status),"Invalid report status");
            var metrics=new ArrayList<Map<String,Object>>();var metricIds=new HashSet<String>();
            for(var item:list(raw.get("metrics"),128)) {
                var metric=map(item);String id=id(metric.get("id"));require(metricIds.add(id),"Duplicate metric");
                var value=new LinkedHashMap<String,Object>();value.put("id",id);value.put("label",text(metric.get("label"),240));
                value.put("value",text(metric.get("value"),2000));value.put("unit",text(metric.get("unit"),128));
                value.put("calculation",text(metric.get("calculation"),4000));metrics.add(value);
            }
            var tables=new ArrayList<Map<String,Object>>();var tableIds=new HashSet<String>();
            for(var item:list(raw.get("tables"),64)) {
                var table=map(item);String id=id(table.get("id"));require(tableIds.add(id),"Duplicate table");
                var columns=list(table.get("columns"),40).stream().map(column->text(column,240)).toList();require(!columns.isEmpty(),"Table columns required");
                var data=new ArrayList<List<Object>>();
                for(var row:list(table.get("rows"),200)) {
                    var cells=list(row,40);require(cells.size()==columns.size(),"Table width mismatch");
                    data.add(cells.stream().map(cell->{require(cell==null||cell instanceof String||cell instanceof Number||cell instanceof Boolean,"Non-scalar cell");return cell==null?null:(Object)text(cell,2000);}).toList());
                }
                tables.add(Map.of("id",id,"title",text(table.get("title"),240),"columns",columns,"rows",data,"scope",text(table.get("scope"),4000)));
            }
            var charts=new ArrayList<Map<String,Object>>();var chartIds=new HashSet<String>();var artifacts=new ArrayList<Map<String,Object>>();
            for(var item:list(raw.get("charts"),64)) {
                var chart=map(item);String chartId=id(chart.get("id"));require(chartIds.add(chartId),"Duplicate chart");
                String kind=text(chart.get("kind"),16),name=text(chart.get("fileName"),255);
                try {
                    require(name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,254}"),"Invalid chart file name");
                    byte[] bytes=files.get("results/"+name);require(bytes!=null&&bytes.length>0&&bytes.length<=8*1024*1024,"Chart file unavailable or exceeds 8MiB");
                    String media;
                    switch(kind){
                        case "png" -> {require(name.endsWith(".png"),"PNG extension required");validatePng(bytes);media="image/png";}
                        case "svg" -> {require(name.endsWith(".svg"),"SVG extension required");validateSvg(bytes);media="image/svg+xml";}
                        case "plotly" -> {require(name.endsWith(".json")&&bytes.length<=2*1024*1024,"Plotly JSON exceeds limit");validatePlotly(json.readValue(bytes,Object.class));media="application/json";}
                        default -> throw new IllegalArgumentException("Unsupported chart artifact");
                    }
                    require(archive!=null,"Object storage unavailable");
                    var stored=archive.save(scope,task,UserAssetService.Kind.ARTIFACT,"execution:"+operation+":results:"+name,name,bytes);
                    var ref=Map.<String,Object>of("fileId",stored.id(),"fileName",name,"sha256",stored.sha256(),"size",stored.size(),"mediaType",media);
                    artifacts.add(ref);charts.add(Map.of("id",chartId,"title",text(chart.get("title"),240),"kind",kind,"artifact",ref,"caption",text(chart.get("caption"),8000)));
                }catch(Exception invalid){warnings.add("Chart "+chartId+" unavailable: "+safeReason(invalid));
                    log.warn("visual_report_chart_rejected task={} operation={} chart={} reason={}",task,operation,chartId,invalid.getClass().getSimpleName());}
            }
            var sections=new ArrayList<Map<String,Object>>();
            var sectionIds=new HashSet<String>();
            for(var item:list(raw.get("sections"),64)) {
                var section=map(item);String sectionId=section.containsKey("id")?id(section.get("id")):"section-"+(sections.size()+1);
                require(sectionIds.add(sectionId),"Duplicate section");
                sections.add(Map.of("id",sectionId,"title",text(section.get("title"),240),"narrative",text(section.get("narrative"),16000),
                    "chartIds",references(section.get("chartIds"),chartIds),"tableIds",references(section.get("tableIds"),tableIds)));
            }
            var presentation=ReportPresentation.read(Map.of("reportPresentation",execution.getOrDefault("reportPresentation",
                raw.getOrDefault("presentation",ReportPresentation.defaults()))));
            result.put("presentation",presentation);
            if(raw.containsKey("layout")){
                try{
                    result.put("layout",ReportLayout.validate(raw.get("layout"),Map.of("metric",metricIds,"section",sectionIds,"chart",charts.stream()
                        .map(chart->(String)chart.get("id")).collect(java.util.stream.Collectors.toSet()),"table",tableIds)));
                    result.put("layoutValidationStatus","PASSED");
                }catch(IllegalArgumentException invalid){
                    result.put("layoutValidationStatus","FAILED");warnings.add("The optional report layout was invalid; original analysis is retained in compatible layout.");
                }
            }else if(execution.containsKey("reportPresentation")){
                result.put("layoutValidationStatus","FAILED");
                warnings.add("The optional report layout was not supplied; original analysis is retained in compatible layout.");
            }
            var validation=map(raw.get("validation"));var checks=new ArrayList<Map<String,Object>>();boolean failed=false,unresolved=false;
            for(var item:list(validation.get("checks"),128)) {
                var check=map(item);String state=text(check.get("status"),16);require(Set.of("PASSED","FAILED","UNRESOLVED").contains(state),"Invalid computation check");failed|="FAILED".equals(state);unresolved|="UNRESOLVED".equals(state);
                checks.add(Map.of("name",text(check.get("name"),240),"status",state,"detail",text(check.get("detail"),4000)));
            }
            for(var warning:list(validation.get("warnings"),64))warnings.add(text(warning,4000));
            if(failed)status="FAILED";
            else if(unresolved||!warnings.isEmpty()||checks.isEmpty()) {if("SUCCESS".equals(status))status="PARTIAL";}
            if(checks.isEmpty())warnings.add("The analysis program supplied no computation checks; business correctness is not certified.");
            if("NO_DATA".equals(status))require(metrics.isEmpty()&&charts.isEmpty()&&tables.stream().allMatch(t->((List<?>)t.get("rows")).isEmpty()),"NO_DATA report contains calculated data");
            result.put("status",status);result.put("metrics",metrics);result.put("tables",tables);result.put("charts",charts);result.put("sections",sections);
            var sources=list(raw.get("dataSources"),64).stream().map(source->text(source,512)).toList();
            var cited=list(rows.get(0).get("evidenceReferences"),64).stream().map(source->text(source,512)).toList();
            require(!sources.isEmpty()&&cited.containsAll(sources),"Report data sources must match the report's authorized citations");
            bindDataReferences(raw,"metrics",metrics,sources);
            bindDataReferences(raw,"tables",tables,sources);
            bindDataReferences(raw,"charts",charts,sources);
            if(result.containsKey("layout")&&java.util.stream.Stream.of(metrics,tables,charts).flatMap(Collection::stream).anyMatch(item->!item.containsKey("dataRefs"))){
                result.remove("layout");result.put("layoutValidationStatus","FAILED");
                warnings.add("The optional layout lacks per-element source references; original analysis is retained in compatible layout.");
                if("SUCCESS".equals(status)){status="PARTIAL";result.put("status",status);}
            }
            for(int index=0;index<charts.size();index++){
                String acceptedId=(String)charts.get(index).get("id");
                var original=list(raw.get("charts"),64).stream().map(VisualReportArtifacts::map)
                    .filter(chart->Objects.equals(chart.get("id"),acceptedId)).findFirst();
                if(original.isPresent()){
                    var chart=new LinkedHashMap<>(charts.get(index));var input=original.get();
                    if(input.containsKey("role")){require(Set.of("chart","illustration").contains(input.get("role")),"Invalid chart role");chart.put("role",input.get("role"));}
                    if(input.containsKey("chartType")){require(Set.of("line","bar","pie","scatter","histogram","box").contains(input.get("chartType")),"Invalid chart type");chart.put("chartType",input.get("chartType"));}
                    charts.set(index,chart);
                }
            }
            var limitations=new LinkedHashSet<String>();
            for(Object value:list(rows.get(0).get("limitations"),64))limitations.add(text(value,4000));
            for(Object value:list(raw.get("limitations"),64))limitations.add(text(value,4000));
            result.put("limitations",List.copyOf(limitations));
            result.put("dataSources",sources);result.put("artifacts",artifacts);result.put("execution",execution);
            var timings=new LinkedHashMap<String,Object>();
            if(raw.get("timings") instanceof Map<?,?> times)for(String key:List.of("analysisMs","chartMs"))if(times.get(key) instanceof Number number){double value=number.doubleValue();require(Double.isFinite(value)&&value>=0&&value<=3600000,"Invalid program timing");timings.put(key,number);}
            // Evidence is collected again during finalization/recovery. Wall-clock admission
            // duration belongs in telemetry, never in the immutable evidence payload/hash.
            long elapsed=(System.nanoTime()-started)/1_000_000;result.put("timings",timings);
            result.put("artifactValidationStatus","PASSED");
            result.put("validation",Map.of("status",failed?"FAILED":unresolved||checks.isEmpty()||!warnings.isEmpty()?"PARTIAL":"ARTIFACTS_VERIFIED","checks",checks,"warnings",warnings,
                "scope","STOPPED_EXECUTION_AND_ARTIFACT_INTEGRITY; COMPUTATION_CHECKS_ARE_PROGRAM_DECLARED; BUSINESS_SEMANTICS_NOT_CERTIFIED"));
            require(json.writeValueAsBytes(result).length<=1024*1024,"Report manifest exceeds 1MiB");
            log.info("visual_report_admission task={} operation={} status={} charts={} metrics={} durationMs={}",task,operation,status,charts.size(),metrics.size(),elapsed);return result;
        }catch(Exception invalid){
            log.warn("visual_report_rejected task={} operation={} reasonType={}",task,operation,invalid.getClass().getSimpleName());
            return Map.of("schemaVersion",VisualReportMode.SCHEMA,"reportMode","visual","status","FAILED","title","Visual report unavailable",
                "summary","The original text analysis is retained; visual report validation failed.","markdown",fallbackMarkdown(rows),
                "artifactValidationStatus","FAILED","validation",Map.of("status","FAILED","warnings",List.of(safeReason(invalid))),"execution",execution);
        }
    }
    private static void bindDataReferences(Map<String,Object> raw,String key,List<Map<String,Object>> accepted,List<String> sources){
        var originals=new HashMap<String,Map<String,Object>>();
        for(Object value:list(raw.get(key),128)){var item=map(value);originals.put(Objects.toString(item.get("id"),""),item);}
        for(int index=0;index<accepted.size();index++){
            var original=originals.get(accepted.get(index).get("id"));
            if(original==null||!original.containsKey("dataRefs"))continue;
            var refs=list(original.get("dataRefs"),64).stream().map(ref->text(ref,512)).toList();
            require(!refs.isEmpty()&&sources.containsAll(refs),"Visual data references must name authorized report sources");
            var item=new LinkedHashMap<>(accepted.get(index));item.put("dataRefs",refs);accepted.set(index,item);
        }
    }
    private static String fallbackMarkdown(List<Map<String,Object>> rows){
        try{return rows.size()==1?text(rows.get(0).get("reportMarkdown"),200000):"";}
        catch(IllegalArgumentException invalid){return "";}
    }
    public static void validatePng(byte[] bytes)throws Exception {
        require(bytes.length>=24&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}),"Invalid PNG");
        try(var input=javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(input);require(readers.hasNext(),"Invalid image");var reader=readers.next();
            try{reader.setInput(input);long width=reader.getWidth(0),height=reader.getHeight(0);require(width>0&&height>0&&width*height<=16_000_000,"Image dimensions exceed limits");require(reader.read(0)!=null,"Invalid PNG pixels");}finally{reader.dispose();}
        }
    }
    public static void validateSvg(byte[] bytes)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        // Matplotlib's standard SVG declaration is inert; no other DTD or entity is admitted.
        String source=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replaceFirst("(?s)<!DOCTYPE svg PUBLIC\\s+\"-//W3C//DTD SVG 1.1//EN\"\\s+\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\"\\s*>","");
        require(!source.matches("(?s).*<\\?(?!xml\\s).*"),"SVG processing instructions forbidden");
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));require("svg".equals(document.getDocumentElement().getLocalName()),"Invalid SVG root");
        var nodes=document.getElementsByTagName("*");require(nodes.getLength()<=100000,"SVG complexity exceeds limit");
        for(int i=0;i<nodes.getLength();i++) {
            var node=nodes.item(i);require(Set.of("http://www.w3.org/2000/svg","http://www.w3.org/1999/02/22-rdf-syntax-ns#","http://purl.org/dc/elements/1.1/","http://creativecommons.org/ns#").contains(node.getNamespaceURI()),"Foreign SVG namespace forbidden");
            require(!Set.of("script","foreignObject","iframe","object","embed","image","set","a").contains(node.getLocalName())&&!node.getLocalName().startsWith("animate"),"Active SVG content forbidden");
            if("style".equals(node.getLocalName()))require(!node.getTextContent().contains("\\")&&!node.getTextContent().contains("@")&&!node.getTextContent().toLowerCase(Locale.ROOT).matches("(?s).*(https?:|javascript:|file:|data:|url\\(\\s*[^#]).*"),"External SVG stylesheet forbidden");
            var attrs=node.getAttributes();for(int j=0;j<attrs.getLength();j++) {
                var attr=attrs.item(j);String name=attr.getNodeName().toLowerCase(Locale.ROOT),value=attr.getNodeValue();
                if(name.equals("xmlns")||name.startsWith("xmlns:"))continue; // Namespace URIs are identifiers, never fetched.
                if("http://www.w3.org/1999/02/22-rdf-syntax-ns#".equals(attr.getNamespaceURI())&&Set.of("resource","about").contains(attr.getLocalName()))continue; // Inert Matplotlib provenance metadata.
                require(!name.startsWith("on")&&!value.toLowerCase(Locale.ROOT).matches("(?s).*(https?:|javascript:|file:|data:|@import).*"),"External SVG resource forbidden");
                if(name.endsWith("href"))require(value.startsWith("#"),"External SVG link forbidden");
                if(name.equals("style"))require(!value.contains("\\")&&!value.contains("@"),"SVG escaped stylesheet forbidden");
                if(value.contains("url("))require(value.matches("(?s)(?:[^u]|u(?!rl\\())*url\\(#[A-Za-z0-9_.-]+\\)(?:[^u]|u(?!rl\\())*"),"External SVG style forbidden");
            }
        }
    }
    public static void validatePlotly(Object value){
        var figure=map(value);require(figure.get("data") instanceof List<?> traces&&!traces.isEmpty()&&traces.size()<=128,"Plotly traces required");
        for(var trace:(List<?>)figure.get("data")){var data=map(trace);require(!Set.of("image","scattergeo","choropleth","scattermapbox","choroplethmapbox","scattermap","choroplethmap").contains(Objects.toString(data.get("type"),"scatter")),"External Plotly renderer forbidden");}
        checkPlotly(value,0,new int[]{0});
    }
    private static void checkPlotly(Object value,int depth,int[] nodes){
        require(depth<40&&++nodes[0]<=300000,"Plotly complexity exceeds limit");
        if(value instanceof Map<?,?> map)map.forEach((key,item)->{String name=key.toString().toLowerCase(Locale.ROOT);require(!Set.of("__proto__","prototype","constructor","source","src","href","url","accesstoken","mapbox","images","bdata").contains(name),"External or executable Plotly content forbidden");checkPlotly(item,depth+1,nodes);});
        else if(value instanceof List<?> list){require(list.size()<=100000,"Plotly points exceed limit");list.forEach(item->checkPlotly(item,depth+1,nodes));}
        else if(value instanceof String text){String stripped=text.replaceAll("(?i)</?(b|i|em|strong|sup|sub|extra)>|<br\\s*/?>","");require(text.length()<=20000&&!stripped.contains("<")&&!stripped.toLowerCase(Locale.ROOT).matches("(?s).*(https?:|javascript:|data:|file:|//).*"),"Unsafe Plotly text or resource");}
        else require(value==null||value instanceof Number||value instanceof Boolean,"Invalid Plotly value");
    }
    private static List<String> references(Object value,Set<String> allowed){return list(value,128).stream().map(VisualReportArtifacts::id).peek(id->require(allowed.contains(id),"Unknown report section reference")).toList();}
    private static String id(Object value){String id=text(value,100);require(id.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}"),"Invalid report item ID");return id;}
    private static String text(Object value,int max){if(value==null)return "";require(value instanceof String||value instanceof Number||value instanceof Boolean,"Scalar text required");String text=value.toString();require(text.length()<=max,"Report text exceeds limit");return text.replaceAll("(?i)(?:[A-Z]:\\\\|/(?:workspace|data/input|srv|opt|var|tmp)/)[^\\s`<>]*","[analysis file]");}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){require(value instanceof Map<?,?>,"Report object required");return (Map<String,Object>)value;}
    private static List<?> list(Object value,int max){if(value==null)return List.of();require(value instanceof List<?> list&&list.size()<=max,"Report list exceeds limit");return (List<?>)value;}
    private static String safeReason(Exception invalid){return invalid instanceof IllegalArgumentException?Objects.toString(invalid.getMessage(),"Invalid report artifact"):"Artifact parsing or storage failed";}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
