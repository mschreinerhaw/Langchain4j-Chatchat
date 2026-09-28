package com.chatchat.api.controller.datascience;
import com.chatchat.api.runtime.*;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.application.SkillCompositionRuntime;
import com.chatchat.runtime.skill.api.execution.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillCompositionControllerTest {
    private final SkillCompositionRuntime runtime=mock(SkillCompositionRuntime.class);
    private final SkillAnalysisRunService runs=mock(SkillAnalysisRunService.class);
    private final SkillDataBindingService bindings=mock(SkillDataBindingService.class);
    private final HttpServletRequest http=mock(HttpServletRequest.class);
    private final SkillCompositionController controller=new SkillCompositionController(runtime,runs,bindings);
    @Test void acceptsBusinessInputsButBuildsIdentityAndExecutionConstraintsOnServer() {
        authenticate();
        var request=new SkillCompositionController.Request("q",List.of("sales.summary"),List.of(),Map.of(),Map.of("tenantId","forged"),null,2);
        controller.plan(request,http);
        var captured=org.mockito.ArgumentCaptor.forClass(SkillCompositionRequest.class);
        verify(runtime).plan(captured.capture());
        assertThat(captured.getValue().identity().tenantId()).isEqualTo("tenant");
        assertThat(captured.getValue().attributes()).containsEntry("maxToolCalls",0).doesNotContainKey("analysisBaseline");
        assertThat(captured.getValue().engine()).isEqualTo("LANGCHAIN4J");
    }
    @Test void experimentsUseOnePinnedPlanAndOneRequestLocalDataSession() {
        authenticate();
        var plan=new SkillCompositionPlan("READY",List.of(),List.of(),Map.of());
        var result=new SkillCompositionResult("COMPLETED_WITH_LIMITATIONS",plan,Map.of(),Map.of(),Map.of());
        when(runtime.plan(any())).thenReturn(plan);
        when(runtime.execute(any(),any(),same(plan))).thenReturn(result);
        when(runs.save(any(),any(),any())).thenReturn("run");
        controller.experiment(new SkillCompositionController.Request("q",List.of("sales.summary"),List.of(),Map.of(),Map.of(),null,2),http);
        var sessions=org.mockito.ArgumentCaptor.forClass(SkillDataSession.class);
        var requests=org.mockito.ArgumentCaptor.forClass(SkillCompositionRequest.class);
        verify(runtime,times(2)).execute(requests.capture(),sessions.capture(),same(plan));
        assertThat(sessions.getAllValues().get(0)).isSameAs(sessions.getAllValues().get(1));
        assertThat(requests.getAllValues().get(1).attributes()).containsEntry("analysisBaseline",true);
        verify(runs).save(any(),any(),any());
    }
    @Test void anonymousAndNonAdminRequestsCannotPublishBindings() {
        assertThatThrownBy(() -> controller.bindings("s",http)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        authenticate();
        assertThatThrownBy(() -> controller.publish("s","data.rows.v1",new SkillCompositionController.Revision(0),http))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(bindings);
    }
    private void authenticate() {
        var user=mock(EnterpriseAdminService.UserView.class);
        when(user.id()).thenReturn("user");when(user.tenantId()).thenReturn("tenant");when(user.status()).thenReturn("enabled");
        when(user.roleIds()).thenReturn(List.of("role"));when(user.username()).thenReturn("analyst");
        when(http.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW)).thenReturn(user);
    }
}

