package com.chatchat.api.runtime.coding.evidence;

import com.chatchat.api.assets.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VisualReportArtifactsTest {
    final ObjectMapper json=new ObjectMapper();final VisualReportArtifacts service=new VisualReportArtifacts(json);
    final KernelDataScope scope=new KernelDataScope("tenant","owner",null,null,"run",null,Map.of());
    final RuntimeAssetArchive archive=mock(RuntimeAssetArchive.class);
    final Map<String,Object> execution=Map.of("taskId","task","executorStopped",true,"codeSha256","a".repeat(64));
    @BeforeEach void setup(){service.setArchive(archive);when(archive.save(eq(scope),eq("task"),any(),anyString(),anyString(),any())).thenAnswer(call->{byte[] data=call.getArgument(5);String name=call.getArgument(4);return new UserAssetService.File(UserAssetService.hash(data),"ARTIFACT",name,"application/octet-stream",UserAssetService.hash(data),data.length,"AVAILABLE","task","run",call.getArgument(3),1);});}
    Map<String,Object> raw(){var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","python_visual_report.v1");value.put("title","Computed analysis");value.put("summary","Computed total is 6.000000000000000001");value.put("status","SUCCESS");value.put("metrics",List.of(Map.of("id","sum","label","Total","value","6.000000000000000001","unit","units","calculation","Python Decimal sum")));value.put("tables",List.of(Map.of("id","summary","title","Summary","columns",List.of("total"),"rows",List.of(List.of("6.000000000000000001")))));value.put("sections",List.of());value.put("charts",List.of());value.put("dataSources",List.of("source:authorized"));value.put("validation",Map.of("checks",List.of(Map.of("name","same-results","status","PASSED","detail","Chart and metrics share computed objects")),"warnings",List.of()));return value;}
    Map<String,Object> collect(Map<String,Object> value,Map<String,byte[]> files){return service.collect(scope,"task","op",List.of(Map.of("visualReport",value,"reportMarkdown","Complete computed narrative","evidenceReferences",List.of("source:authorized"))),files,execution);}
    byte[] png()throws Exception{var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);return bytes.toByteArray();}
    @Test void preservesExactComputedMetricsAndAdmitsStaticAndPlotlyFilesFromTheSameExecution()throws Exception{
        var report=raw();report.put("charts",List.of(Map.of("id","static","title","Static","kind","png","fileName","static.png"),Map.of("id","interactive","title","Interactive","kind","plotly","fileName","interactive.json")));
        var figure=Map.of("data",List.of(Map.of("type","scatter","x",List.of("a","b"),"y",List.of(1,2))));
        var result=collect(report,Map.of("results/static.png",png(),"results/interactive.json",json.writeValueAsBytes(figure)));
        assertThat(result.get("status")).isEqualTo("SUCCESS");assertThat((List<?>)result.get("charts")).hasSize(2);
        assertThat(result.toString()).contains("6.000000000000000001","executorStopped=true","sha256=").doesNotContain("/workspace/","<script");
        verify(archive).save(eq(scope),eq("task"),eq(UserAssetService.Kind.ARTIFACT),eq("execution:op:results:static.png"),eq("static.png"),any());
    }
    @Test void missingOrMaliciousChartKeepsValidTextAndMarksReportPartial(){
        var report=raw();report.put("charts",List.of(Map.of("id","missing","kind","png","fileName","missing.png"),Map.of("id","traversal","kind","png","fileName","../other-user.png")));
        var result=collect(report,Map.of());assertThat(result.get("status")).isEqualTo("PARTIAL");assertThat(result.get("markdown")).isEqualTo("Complete computed narrative");assertThat((List<?>)result.get("charts")).isEmpty();verifyNoInteractions(archive);
    }
    @Test void failedComputationChecksCannotBePublishedAsSuccess(){var report=raw();report.put("validation",Map.of("checks",List.of(Map.of("name","consistency","status","FAILED"))));assertThat(collect(report,Map.of()).get("status")).isEqualTo("FAILED");}
    @Test void unresolvedReconciliationPreservesReportAndChartsWithoutCertifyingBusinessSuccess()throws Exception{
        var report=raw();report.put("validation",Map.of("checks",List.of(Map.of("name","computed","status","PASSED"),Map.of("name","reconciliation","status","UNRESOLVED","detail","Insufficient evidence"))));
        report.put("charts",List.of(Map.of("id","plot","kind","png","fileName","plot.png")));
        var result=collect(report,Map.of("results/plot.png",png()));
        assertThat(result).containsEntry("status","PARTIAL").containsEntry("artifactValidationStatus","PASSED").containsEntry("markdown","Complete computed narrative");
        assertThat((List<?>)result.get("charts")).hasSize(1);assertThat(result.get("metrics")).isEqualTo(report.get("metrics"));
        assertThat(((Map<?,?>)result.get("validation")).get("status")).isEqualTo("PARTIAL");
        assertThat(result.toString()).contains("UNRESOLVED","Insufficient evidence");
        report.put("validation",Map.of("checks",List.of(Map.of("name","unresolved","status","UNRESOLVED"),Map.of("name","failed","status","FAILED"))));
        assertThat(collect(report,Map.of("results/plot.png",png()))).containsEntry("status","FAILED").containsEntry("artifactValidationStatus","PASSED");
    }
    @Test void malformedCheckRetainsSanitizedMarkdownAndRemainsATechnicalFailure(){
        var report=raw();report.put("validation",Map.of("checks",List.of(Map.of("name","bad","status","UNKNOWN"))));
        var result=service.collect(scope,"task","op",List.of(Map.of("visualReport",report,"reportMarkdown","Retained text /workspace/private.txt","evidenceReferences",List.of("source:authorized"))),Map.of(),execution);
        assertThat(result).containsEntry("status","FAILED").containsEntry("artifactValidationStatus","FAILED").containsEntry("markdown","Retained text [analysis file]");
        assertThat(result.toString()).contains("Invalid computation check").doesNotContain("/workspace/private.txt");
    }
    @Test void admissionTelemetryCannotChangeImmutableReportBytesOnReplay()throws Exception{
        var counter=new java.util.concurrent.atomic.AtomicInteger();
        when(archive.save(eq(scope),eq("task"),any(),anyString(),anyString(),any())).thenAnswer(call->{
            if(counter.getAndIncrement()==0)Thread.sleep(35);
            byte[] bytes=call.getArgument(5);return new UserAssetService.File(UserAssetService.hash(bytes),"ARTIFACT","plot.png","image/png",UserAssetService.hash(bytes),bytes.length,"AVAILABLE","task","run","source",1);
        });
        var report=raw();report.put("charts",List.of(Map.of("id","plot","kind","png","fileName","plot.png")));
        var files=Map.of("results/plot.png",png());var first=collect(report,files);var replay=collect(report,files);
        assertThat(first.get("status")).isEqualTo("SUCCESS");assertThat(replay).isEqualTo(first);
        assertThat(json.writeValueAsBytes(replay)).isEqualTo(json.writeValueAsBytes(first));
    }
    @Test void noDataCannotCarryInventedMetrics(){var report=raw();report.put("status","NO_DATA");assertThat(collect(report,Map.of()).get("status")).isEqualTo("FAILED");report.put("metrics",List.of());report.put("tables",List.of());assertThat(collect(report,Map.of()).get("status")).isEqualTo("NO_DATA");}
    @Test void sourceReferencesMustMatchTheReportCitations(){var report=raw();report.put("dataSources",List.of("other-owner:source"));assertThat(collect(report,Map.of()).get("status")).isEqualTo("FAILED");}
    @Test void unrecognizedReportAndInvalidTableAreNotMarkedSuccessful(){assertThat(collect(Map.of(),Map.of()).get("status")).isEqualTo("FAILED");var report=raw();report.put("tables",List.of(Map.of("id","bad","columns",List.of("a","b"),"rows",List.of(List.of(1)))));assertThat(collect(report,Map.of()).get("status")).isEqualTo("FAILED");}
    @Test void rejectsExecutableOrNetworkPlotlyPayloads(){
        for(Object bad:List.of(Map.of("data",List.of(Map.of("type","scatter","x",List.of("<script>alert(1)</script>")))),Map.of("data",List.of(Map.of("type","image","source","https://example.invalid/leak"))),Map.of("data",List.of(Map.of("type","scatter","url","https://example.invalid"))),Map.of("data",List.of(Map.of("type","scatter","__proto__",Map.of("polluted",true))))))assertThatThrownBy(()->VisualReportArtifacts.validatePlotly(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->VisualReportArtifacts.validatePlotly(Map.of("data",List.of(Map.of("type","scatter","x",List.of(1),"hovertemplate","x=%{x}<br><extra></extra>"))))).doesNotThrowAnyException();
    }
    @Test void rejectsActiveSvgExternalResourcesAndEntityExpansion()throws Exception{
        for(String bad:List.of("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>","<svg xmlns='http://www.w3.org/2000/svg'><use href='https://example.invalid/a.svg'/></svg>","<!DOCTYPE svg [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]><svg xmlns='http://www.w3.org/2000/svg'>&steal;</svg>"))assertThatThrownBy(()->VisualReportArtifacts.validateSvg(bad.getBytes(StandardCharsets.UTF_8))).isInstanceOf(Exception.class);
        VisualReportArtifacts.validateSvg("<svg xmlns='http://www.w3.org/2000/svg'><defs><path id='p'/></defs><use href='#p'/></svg>".getBytes(StandardCharsets.UTF_8));
    }
    @Test void acceptsActualMatplotlibSvgIncludingItsInertProvenanceAndStandardDeclaration()throws Exception{
        try(var input=getClass().getResourceAsStream("/visual-report/matplotlib.svg")){assertThat(input).isNotNull();VisualReportArtifacts.validateSvg(input.readAllBytes());}
    }
}
