package com.chatchat.api.controller.datascience;

import com.chatchat.api.runtime.SkillAnalysisRunService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.application.SkillIntelligenceLayer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SkillIntelligenceControllerTest {
    private final SkillIntelligenceLayer intelligence = mock(SkillIntelligenceLayer.class);
    private final SkillAnalysisRunService runs = mock(SkillAnalysisRunService.class);
    private final EnterpriseAdminService authorization = mock(EnterpriseAdminService.class);
    private final HttpServletRequest http = mock(HttpServletRequest.class);
    private final SkillIntelligenceController controller = new SkillIntelligenceController(intelligence, runs, authorization);
    private SkillIntelligenceController.Request body(String engine) {
        return new SkillIntelligenceController.Request("Analyze", "agent", engine, "model", List.of(), List.of(),
            Map.of(), Map.of("tenantId", "forged"), 4);
    }
    private void authenticate() {
        var user = mock(EnterpriseAdminService.UserView.class);
        when(user.id()).thenReturn("user"); when(user.tenantId()).thenReturn("tenant");
        when(user.status()).thenReturn("enabled"); when(user.roleIds()).thenReturn(List.of("role"));
        when(http.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW)).thenReturn(user);
    }
    @Test void rejectsAnonymousAndUnauthorizedAgentBeforePlanning() {
        assertThatThrownBy(() -> controller.plan(body(null), http)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        authenticate();
        assertThatThrownBy(() -> controller.plan(body(null), http)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(intelligence, runs);
    }
    @Test void buildsIdentityFromAuthenticationAndDefaultsToNativeAdk() {
        authenticate(); when(authorization.canAccessAgent("user", "agent")).thenReturn(true);
        controller.plan(body(null), http);
        var capture = ArgumentCaptor.forClass(SkillCompositionRequest.class); verify(intelligence).plan(capture.capture());
        assertThat(capture.getValue().identity().tenantId()).isEqualTo("tenant");
        assertThat(capture.getValue().identity().attributes()).containsEntry("agentId", "agent");
        assertThat(capture.getValue().engine()).isEqualTo("GOOGLE_ADK_NATIVE");
        assertThat(capture.getValue().attributes()).doesNotContainKey("skillDataResults");
    }
    @Test void rejectsUnavailableEngineRatherThanSilentlyFallingBack() {
        authenticate(); when(authorization.canAccessAgent("user", "agent")).thenReturn(true);
        assertThatThrownBy(() -> controller.plan(body("SPRING_AI"), http)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(intelligence);
    }
}
