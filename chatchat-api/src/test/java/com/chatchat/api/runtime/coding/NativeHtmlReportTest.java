package com.chatchat.api.runtime.coding;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.nativecline.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeHtmlReportTest {
    UserAssetService.File file(String id,String kind,String run,String path){return new UserAssetService.File(id,kind,path.substring(path.lastIndexOf('/')+1),"application/octet-stream","hash",100,"AVAILABLE","task",run,"coding-node:v1:"+path,1);}
    @Test void inventoriesOnlySameTaskRunDeliveryFilesAndKeepsNestedPaths(){
        var html=file("html","ARTIFACT","run","output/site/report.html");var js=file("js","CODE","run","output/site/app.js");
        var resources=NativeHtmlReport.resources("task","run",List.of(html,js,file("foreign","ARTIFACT","other","output/foreign.json")));
        assertThat(resources).containsExactly(html,js);assertThat(NativeHtmlReport.descriptor(html)).containsEntry("entryPath","output/site/report.html");
    }
    @Test void rejectsTraversalAndAmbiguousPaths(){
        assertThatThrownBy(()->NativeHtmlReport.path(file("bad","ARTIFACT","run","output/../secret.html"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->NativeHtmlReport.resources("task","run",List.of(file("one","ARTIFACT","run","output/a.js"),file("two","CODE","run","output/a.js")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void htmlOnlyDeliveryProjectsWithoutInventingMarkdownChartsOrBusinessValidation(){
        var assets=mock(UserAssetService.class);var report=new NativeVisualReport(assets,new ObjectMapper()).project(new KernelDataScope("t","u",null,null,"run",null,Map.of()),"task",Map.of("reportMode","visual"),List.of(),List.of(file("html","ARTIFACT","run","output/site/report.html"))).orElseThrow();
        assertThat(report).containsKey("htmlReport").containsEntry("markdown","").containsEntry("businessValidationPassed",false);
        assertThat((List<?>)report.get("charts")).isEmpty();verifyNoInteractions(assets);
        assertThat(NativeVisualReport.instruction(Map.of("reportMode","visual"))).contains("exactly one","HTML","no CDN","Local fetch");
    }
}
