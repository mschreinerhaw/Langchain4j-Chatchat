package com.chatchat.api.controller.datascience;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.runtime.skill.api.execution.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.execution.SkillExecutionResult;
import com.chatchat.runtime.skill.application.SkillDataAcquisition;
import com.chatchat.runtime.skill.port.inbound.SkillRuntime;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SkillDataAnalysisControllerTest {
    private final SkillRuntime runtime = mock(SkillRuntime.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final SkillDataAnalysisController controller = new SkillDataAnalysisController(runtime);

    @Test void identityComesFromAuthenticationAndOnlyBusinessInputsReachRuntime() {
        authenticate();
        when(runtime.execute(any())).thenReturn(new SkillExecutionResult("COMPLETED", null, null, null, null, Map.of()));
        var response = controller.analyze("domain", new SkillDataAnalysisController.AnalysisRequest("analyze", "workflow",
            "published-model", Map.of("customerId", "c1", "tenantId", "forged")), request);
        var captured = org.mockito.ArgumentCaptor.forClass(SkillExecutionRequest.class);
        verify(runtime).execute(captured.capture());
        assertThat(captured.getValue().roleContext().tenantId()).isEqualTo("tenant");
        assertThat(captured.getValue().roleContext().userId()).isEqualTo("user");
        assertThat(captured.getValue().roleContext().roleIds()).containsExactly("role");
        assertThat(captured.getValue().requestedSkillIds()).containsExactly("domain");
        assertThat(captured.getValue().engine()).isEqualTo("LANGCHAIN4J");
        assertThat(captured.getValue().intent()).containsEntry("workflowType", "DATA_ANALYSIS")
            .containsEntry("dataContractsRequired", true);
        assertThat(captured.getValue().attributes()).doesNotContainKey(SkillDataAcquisition.RESULTS);
        assertThat(response.getData().status()).isEqualTo("COMPLETED");
    }

    @Test void unauthenticatedRequestDoesNotReachRuntime() {
        assertThatThrownBy(() -> controller.analyze("domain",
            new SkillDataAnalysisController.AnalysisRequest("analyze", "workflow", "model", Map.of()), request))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(runtime);
    }

    @Test void nestedToolPayloadIsNotAcceptedAsBusinessInput() {
        authenticate();
        assertThatThrownBy(() -> controller.analyze("domain", new SkillDataAnalysisController.AnalysisRequest(
            "analyze", "workflow", "model", Map.of("tool", Map.of("name", "sql", "arguments", Map.of()))), request))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(runtime);
    }

    private void authenticate() {
        var user = mock(EnterpriseAdminService.UserView.class);
        when(user.id()).thenReturn("user");
        when(user.tenantId()).thenReturn("tenant");
        when(user.status()).thenReturn("enabled");
        when(user.roleIds()).thenReturn(List.of("role"));
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW)).thenReturn(user);
    }
}
