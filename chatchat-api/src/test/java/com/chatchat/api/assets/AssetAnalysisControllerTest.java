package com.chatchat.api.assets;

import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisService;
import com.chatchat.api.security.ObjectAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssetAnalysisControllerTest {
    UserAssetService assets;CodingAnalysisService coding;AgentChatModelResolver models;ObjectAccessService access;RuntimeAssetArchive archive;
    MockHttpServletRequest request;AssetAnalysisController controller;
    @BeforeEach void setup(){
        assets=mock(UserAssetService.class);coding=mock(CodingAnalysisService.class);models=mock(AgentChatModelResolver.class);
        access=mock(ObjectAccessService.class);archive=mock(RuntimeAssetArchive.class);request=new MockHttpServletRequest();
        when(access.tenant(request)).thenReturn("tenant");when(access.user(request)).thenReturn("alice");
        when(models.checkpointModelConfiguration(any(),any())).thenReturn(Map.of("selectedModel","configured"));
        when(coding.executePrimaryAnalysis(any(),anyMap(),any())).thenReturn(Map.of("resultAvailable",false,"status","RUNNING"));
        controller=new AssetAnalysisController(assets,coding,models,access,archive);
    }
    @Test void crossOwnerSelectionFailsBeforeAnyModelOrExecution(){
        when(assets.get(any(),eq("foreign"))).thenThrow(new SecurityException("not owned"));
        assertThatThrownBy(()->controller.analyze(request,new AssetAnalysisController.Request("request-1",List.of("foreign"),List.of(),"calculate",null))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(models,coding,archive);
    }
    @Test void transportUsesExactOriginalBytesAndOnlyExplicitlyChosenContext(){
        byte[] original="a,b\n1,2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(assets.get(any(),eq("file"))).thenReturn(new UserAssetService.File("file","DOCUMENT","input.csv","text/csv",UserAssetService.hash(original),original.length,"AVAILABLE","","","upload",1));
        when(assets.read(any(),eq("file"))).thenReturn(original);
        controller.analyze(request,new AssetAnalysisController.Request("request-1",List.of("file"),List.of(),"sum b",null));
        var context=org.mockito.ArgumentCaptor.forClass(AnalysisCoverageCoordinator.Request.class);
        verify(coding).executePrimaryAnalysis(context.capture(),argThat(sources->{
            if(sources.size()!=1)return false;var row=sources.values().iterator().next().handle().readPage(0,1).rows().get(0);
            return Arrays.equals(Base64.getDecoder().decode((String)row.get("contentBase64")),original);
        }),any());
        assertThat(context.getValue().isolationScope().userId()).isEqualTo("alice");
        assertThat(context.getValue().runtimeAttributes()).containsKey("checkpointModelConfig");
        assertThat(context.getValue().query()).contains("untrusted DATA");
        verify(assets,never()).list(any(),any(),any(),anyInt());
    }
    @Test void missingAuthenticationAndUnboundedRequestsCannotDispatch(){
        when(access.user(request)).thenReturn(null);
        assertThatThrownBy(()->controller.analyze(request,new AssetAnalysisController.Request("request-1",List.of("file"),null,"sum",null))).hasMessageContaining("401");
        verifyNoInteractions(assets,coding,models);
    }
    @Test void taskCodeCannotBeSubmittedAsHistoricalContext(){
        var file=new UserAssetService.File("file","CODE","main.py","text/plain","a".repeat(64),1,"AVAILABLE","task","run","code",1);
        when(assets.get(any(),anyString())).thenReturn(file);
        assertThatThrownBy(()->controller.analyze(request,new AssetAnalysisController.Request("request-1",List.of("file"),null,"run it",null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(coding,models);
    }
}
