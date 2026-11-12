package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.nativecline.NativeVisualReport;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class NativeVisualReportTest {
    @Test void declaredSceneRequiresResolvingTheSelectedProfileWithoutGuessingAColorConvention(){
        assertThat(NativeVisualReport.instruction(Map.of("reportMode","visual","reportPresentation",Map.of("styleProfile","financial-cn","scenario","financial-analysis"))))
            .contains("Declared visualization scene: financial-analysis","Selected style profile: financial-cn","Resolve its definition","use neutral colors");
        assertThat(NativeVisualReport.instruction(Map.of())).isEmpty();
    }

    final UserAssetService assets=mock(UserAssetService.class);
    final NativeVisualReport reports=new NativeVisualReport(assets,new ObjectMapper());
    final KernelDataScope scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
    UserAssetService.File file(String run){return new UserAssetService.File("chart-id","ARTIFACT","actual-plot.png","application/octet-stream","hash",100,"AVAILABLE","task",run,"coding-node:v1:charts/actual-plot.png",1);}
    final List<Map<String,Object>> rows=List.of(Map.of("reportMarkdown","# 实际分析\n\n结果来自实际执行。","evidenceReferences",List.of("authorized-source"),"limitations",List.of()));
    @Test void projectsOnlyArchivedRealImagesAndPreservesNarrativeAndPreferences()throws Exception{
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",out);when(assets.readTask(scope,"task","chart-id")).thenReturn(out.toByteArray());
        var report=reports.project(scope,"task",Map.of("reportMode","visual","reportPresentation",Map.of("density","compact")),rows,List.of(file("run"))).orElseThrow();
        assertThat(report).containsEntry("schemaVersion","python_visual_report.v1").containsEntry("status","SUCCESS").containsEntry("businessValidationPassed",false);
        var charts=(List<Map<String,Object>>)report.get("charts");assertThat((Map<String,Object>)charts.get(0).get("artifact")).containsKey("fileId");assertThat(report.get("markdown")).isEqualTo(rows.get(0).get("reportMarkdown"));
    }
    @Test void offOrForeignRunCannotReadOrExposeArtifacts(){
        assertThat(reports.project(scope,"task",Map.of(),rows,List.of(file("run")))).isEmpty();
        assertThat(reports.project(scope,"task",Map.of("reportMode","visual"),rows,List.of(file("foreign")))).isEmpty();verifyNoInteractions(assets);
    }
    @Test void corruptOrMissingChartPreservesTextWithExplicitPresentationFailure(){
        when(assets.readTask(scope,"task","chart-id")).thenThrow(new IllegalStateException("Fixture integrity mismatch"));
        var report=reports.project(scope,"task",Map.of("reportMode","visual"),rows,List.of(file("run"))).orElseThrow();
        assertThat(report).containsEntry("status","PARTIAL");assertThat(report.get("markdown")).isEqualTo(rows.get(0).get("reportMarkdown"));
        assertThat(((List<Map<String,Object>>)report.get("charts")).get(0)).containsEntry("renderError","CHART_ARTIFACT_REJECTED").doesNotContainKey("artifact");
    }
    @Test void emptyOrNonQuantitativeEvidenceDoesNotInventCharts(){
        assertThat(reports.project(scope,"task",Map.of("reportMode","visual"),rows,List.of())).isEmpty();verifyNoInteractions(assets);
        assertThat(NativeVisualReport.instruction(Map.of())).isEmpty();assertThat(NativeVisualReport.instruction(Map.of("reportMode","visual"))).contains("insufficient","false autoCharts/autoImages");
    }
}
