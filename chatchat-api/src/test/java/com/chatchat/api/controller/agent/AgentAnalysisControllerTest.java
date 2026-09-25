package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAnalysisControllerTest {
    @Test void buildsContextFromAuthenticatedAndAuthorizedScope() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of("authorized-doc"), List.of("policy"),
                List.of("analyst"), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");
        var controller = new AgentAnalysisController(runtime, scopes);

        controller.analyze(new AgentAnalysisController.AnalyzeRequest("Analyze risk", "local-skill",
            "finance.risk.v1", List.of("requested-doc"), List.of(), 9, 500_000L), request);

        assertThat(observed.get().kernelScope().tenantId()).isEqualTo("tenant-1");
        assertThat(observed.get().documentIds()).containsExactly("authorized-doc");
        assertThat(observed.get().attributes()).containsEntry("agentMaxAttempts", 3)
            .containsEntry("agentTimeoutMs", 120_000L);
    }

    @Test void rejectsAnUnauthorizedLocalSkillBeforeAnalysis() {
        AnalysisRuntimePort runtime = context -> { throw new AssertionError("must not execute"); };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            SkillExecutionScopePort.EffectiveScope.denied(List.of());
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        assertThatThrownBy(() -> new AgentAnalysisController(runtime, scopes).analyze(
            new AgentAnalysisController.AnalyzeRequest("Analyze", "not-granted",
                "finance.risk.v1", List.of(), List.of(), null, null), request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }
}
