package com.chatchat.api.controller.agent;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.runtime.RegisteredToolAnalysisOperator;
import com.chatchat.api.runtime.PreauthorizedStructuredDataOperator;
import com.chatchat.api.runtime.VerifiedEvidenceComputationOperator;
import com.chatchat.api.runtime.GovernedExternalResearchOperator;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisCapability;
import com.chatchat.common.runtime.analysis.spi.AnalysisRuntimePort;
import com.chatchat.common.runtime.agent.AgentCollaborationPlan;
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
    @Test void collaborationEndpointBuildsBoundedPlanInsideExistingAnalysisRuntime() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of("authorized-doc"), List.of(),
                List.of("analyst"), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");
        var controller = new AgentAnalysisController(runtime, scopes);
        controller.collaborate(new AgentAnalysisController.CollaborateRequest("Analyze", "skill-1",
            List.of("requested-doc"), List.of(), List.of(
                Map.of("taskId", "first", "agentId", "local.agent", "capability", "finance.risk.v1",
                    "instruction", "Analyze facts", "mode", "DOMAIN_INFERENCE"),
                Map.of("taskId", "review", "agentId", "group.agent", "capability", "finance.risk.v1",
                    "instruction", "Review first", "mode", "AGENTIC_EXECUTION", "dependsOn", List.of("first"))),
            2, 60000L, null, null, null, null), request);

        assertThat(observed.get().documentIds()).containsExactly("authorized-doc");
        AgentCollaborationPlan plan = (AgentCollaborationPlan) observed.get().attributes()
            .get(AgentCollaborationPlan.CONTEXT_ATTRIBUTE);
        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks().get(1).dependsOn()).containsExactly("first");
    }

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

    @Test void singleAgentAnalysisCarriesOnlyApprovedTemplateIdentityForLaterSupplementation() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        new AgentAnalysisController(runtime, scopes).analyze(
            new AgentAnalysisController.AnalyzeRequest("Analyze sales", "sales-skill", "finance.sales.v1",
                List.of(), List.of(), 2, 60000L, "SALES_TOTAL", "sales", "PROD", Map.of("year", 2025)),
            request);

        assertThat(observed.get().intent().requiredCapabilities()).containsExactly(AnalysisCapability.DOMAIN_INTELLIGENCE);
        assertThat(observed.get().attributes()).containsEntry(PreauthorizedStructuredDataOperator.TEMPLATE_ID,
            "SALES_TOTAL");
    }

    @Test void toolAnalysisUsesAuthenticatedScopeAndToolIntent() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of("analyst"), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        new AgentAnalysisController(runtime, scopes).analyzeTool(
            new AgentAnalysisController.ToolAnalyzeRequest("Find revenue", "finance",
                "revenue_query", Map.of("year", 2025)), request);

        assertThat(observed.get().intent().requiredCapabilities()).containsExactly(AnalysisCapability.TOOL_CALL);
        assertThat(observed.get().attributes()).containsEntry(RegisteredToolAnalysisOperator.TOOL_NAME,
            "revenue_query");
        assertThat(observed.get().kernelScope().userId()).isEqualTo("user-1");
    }

    @Test void compositeAnalysisPlansAuthorizedEvidenceBeforeAgent() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of("allowed-doc"), List.of(),
                List.of("analyst"), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        new AgentAnalysisController(runtime, scopes).analyzeComposite(
            new AgentAnalysisController.CompositeAnalyzeRequest("Analyze the metric", "finance",
                "finance.risk.v1", List.of("requested-doc"), List.of(), "read_tool",
                Map.of("year", 2025), 2, 60000L), request);

        assertThat(observed.get().intent().requiredCapabilities()).containsExactlyInAnyOrder(
            AnalysisCapability.DOCUMENT_SEARCH, AnalysisCapability.TOOL_CALL,
            AnalysisCapability.DOMAIN_INTELLIGENCE);
        assertThat(observed.get().documentIds()).containsExactly("allowed-doc");
        assertThat(observed.get().attributes()).containsEntry(RegisteredToolAnalysisOperator.TOOL_NAME,
            "read_tool");
    }

    @Test void compositeAnalysisCanRequirePreauthorizedDataTemplate() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        new AgentAnalysisController(runtime, scopes).analyzeComposite(
            new AgentAnalysisController.CompositeAnalyzeRequest("Analyze sales", "sales-skill",
                "finance.sales.v1", List.of(), List.of(), null, null, 2, 60000L,
                "SALES_TOTAL", "sales", "PROD", Map.of("year", 2025), "SUM", "amount"), request);

        assertThat(observed.get().intent().requiredCapabilities()).containsExactlyInAnyOrder(
            AnalysisCapability.STRUCTURED_DATA, AnalysisCapability.COMPUTATION,
            AnalysisCapability.DOMAIN_INTELLIGENCE);
        assertThat(observed.get().attributes()).containsEntry(PreauthorizedStructuredDataOperator.TEMPLATE_ID,
            "SALES_TOTAL").containsEntry(VerifiedEvidenceComputationOperator.OPERATION, "SUM");
    }

    @Test void compositeCanRequireGovernedResearchBeforeAgent() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of(), List.of(), List.of(), true, true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");

        new AgentAnalysisController(runtime, scopes).analyzeComposite(
            new AgentAnalysisController.CompositeAnalyzeRequest("Research trends", "research-skill",
                "finance.trends.v1", List.of(), List.of(), null, null, 2, 60000L,
                null, null, null, null, null, null, "mcp_news_web_search", List.of("public trends")), request);

        assertThat(observed.get().intent().requiredCapabilities()).containsExactlyInAnyOrder(
            AnalysisCapability.EXTERNAL_RESEARCH, AnalysisCapability.DOMAIN_INTELLIGENCE);
        assertThat(observed.get().intent().freshness()).isEqualTo("CURRENT");
        assertThat(observed.get().attributes()).containsEntry(GovernedExternalResearchOperator.TOOL_NAME,
            "mcp_news_web_search").containsEntry(GovernedExternalResearchOperator.SEARCH_TERMS,
            List.of("public trends"));
    }
}
