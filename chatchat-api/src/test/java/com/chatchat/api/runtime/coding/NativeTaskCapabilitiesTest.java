package com.chatchat.api.runtime.coding;

import com.chatchat.api.runtime.coding.nativecline.*;
import com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.chat.skills.release.AgentReleaseService;
import com.chatchat.chat.skills.runtime.SkillAnalysisContextService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class NativeTaskCapabilitiesTest {
    final CodingInternetAccess internet=mock(CodingInternetAccess.class);
    final SkillExecutionScopePort scopes=mock(SkillExecutionScopePort.class);
    final AgentReleaseService releases=mock(AgentReleaseService.class);
    final SkillAnalysisContextService catalogs=mock(SkillAnalysisContextService.class);
    final KernelDataScope scope=new KernelDataScope("tenant","user",null,null,"run",null,Map.of());
    final NativeTaskCapabilities service=new NativeTaskCapabilities(internet,mock(NativeS3Settings.class),releases,scopes,catalogs);
    @Test void genericTasksDoNotGainUnrequestedAgentCapabilities(){
        assertThat(service.forAgent(scope,null,"model",true,"objective")).isEmpty();verifyNoInteractions(scopes,releases,catalogs,internet);
    }
    @Test void agentIdentityWithoutLiveGrantCannotExposeAnyCatalog(){
        when(scopes.resolve("tenant","user","other",List.of(),List.of())).thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThatThrownBy(()->service.forAgent(scope,"other","model",true,"objective")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(releases,catalogs,internet);
    }
    @Test void unpublishedAgentIsDeniedEvenWhenTheAccountCanSeeItsDraft(){
        when(scopes.resolve("tenant","user","agent",List.of(),List.of())).thenReturn(new SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of(),true,true));
        when(releases.resolvePublished("agent")).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.forAgent(scope,"agent","model",true,"objective")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(catalogs,internet);
    }
    @Test void snapshotUsesExistingInternetPolicyAndDoesNotInventAProvider(){
        when(internet.snapshot(scope,"agent","model",false)).thenReturn(Map.of());
        when(internet.availability(scope,"agent","model",false,false)).thenReturn(CodingInternetAccess.availabilityStatus("UNAVAILABLE","MODEL_INTERNET_DISABLED"));
        var value=service.capture(scope,"model",Map.of("authorizationAgentId","agent"),Map.of());
        assertThat(value).containsKeys("model","skills").doesNotContainKey("internetSearch");verify(internet).snapshot(scope,"agent","model",false);verifyNoInteractions(scopes,releases,catalogs);
    }
}
