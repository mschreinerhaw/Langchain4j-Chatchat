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
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAnalysisControllerTest {
    @Test void multiSkillKeepsIndependentScopesAndRejectsForgedToolSkill() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of("doc-" + skill), List.of(),
                List.of("analyst"), true, true);
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentDescriptor provider = new AgentDescriptor("group.analysis", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://group.example/a2a"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of("DocumentAnalysisEvidence",
                "ToolAnalysisEvidence"), null, "", 50, true,
            Map.of("allowedTenantIds", List.of("tenant-1"),
                "supportedExecutionModes", List.of("DOMAIN_INFERENCE"),
                "analysisGrants", Map.of("skillIds", List.of("one", "two"),
                    "documentIds", List.of("doc-one", "doc-two"),
                    "mcpToolNames", List.of("market_read"), "allowDataSupplement", false,
                    "defaultInstruction", "Use only supplied evidence")));
        when(registry.find("group.analysis")).thenReturn(Optional.of(provider));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");
        var controller = new AgentAnalysisController(runtime, scopes, registry);
        var skills = List.of(new AgentAnalysisController.DomainSkillChoice("one", List.of("doc-one")),
            new AgentAnalysisController.DomainSkillChoice("two", List.of("doc-two")));
        controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest("Analyze", null,
            "group.analysis", "finance.analysis.v1", List.of(), List.of(),
            List.of(new AgentAnalysisController.DomainToolCall("two", "market_read", Map.of())),
            null, null, null, null, 1, 60000L, true, skills, null), request);
        assertThat(observed.get().attributes().get(AnalysisContext.SKILL_SELECTIONS_ATTRIBUTE))
            .isEqualTo(List.of(new com.chatchat.common.runtime.analysis.model.AnalysisSkillSelection(
                "one", List.of("doc-one"), List.of("analyst")),
                new com.chatchat.common.runtime.analysis.model.AnalysisSkillSelection(
                    "two", List.of("doc-two"), List.of("analyst"))));
        assertThat(observed.get().attributes().get(AnalysisContext.DEFAULT_INSTRUCTION_ATTRIBUTE))
            .isEqualTo("Use only supplied evidence");
        assertThatThrownBy(() -> controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest(
            "Analyze", null, "group.analysis", "finance.analysis.v1", List.of(), List.of(),
            List.of(new AgentAnalysisController.DomainToolCall("other", "market_read", Map.of())),
            null, null, null, null, 1, 60000L, true, skills, null), request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not selected");
        assertThatThrownBy(() -> controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest(
            "Analyze", null, "group.analysis", "finance.analysis.v1", List.of(), List.of(),
            List.of(new AgentAnalysisController.DomainToolCall("two", "unlisted", Map.of())),
            null, null, null, null, 1, 60000L, true, skills, null), request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("provider analysis grants");
        assertThatThrownBy(() -> controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest(
            "Analyze", null, "group.analysis", "finance.analysis.v1", List.of(), List.of(),
            List.of(), null, null, null, null, 1, 60000L, true,
            List.of(new AgentAnalysisController.DomainSkillChoice("three", List.of("doc-three"))), null), request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("provider analysis grants");
        AgentDescriptor noDocumentGrant = new AgentDescriptor("group.analysis", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://group.example/a2a"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of("DocumentAnalysisEvidence"),
            null, "", 50, true, Map.of("allowedTenantIds", List.of("tenant-1"),
                "supportedExecutionModes", List.of("DOMAIN_INFERENCE"),
                "analysisGrants", Map.of("skillIds", List.of("one"), "documentIds", List.of(),
                    "mcpToolNames", List.of(), "allowDocumentSupplement", false)));
        when(registry.find("group.analysis")).thenReturn(Optional.of(noDocumentGrant));
        assertThatThrownBy(() -> controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest(
            "Analyze", null, "group.analysis", "finance.analysis.v1", List.of(), List.of(),
            List.of(), null, null, null, null, 1, 60000L, true,
            List.of(new AgentAnalysisController.DomainSkillChoice("one", List.of("doc-one"))), null), request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Document is outside provider analysis grants");
    }
    @Test void domainAnalysisPinsTenantAdmittedProviderAndPlansEvidenceBeforeInference() {
        AtomicReference<AnalysisContext> observed = new AtomicReference<>();
        AnalysisRuntimePort runtime = context -> {
            observed.set(context);
            return new AnalysisExecutionOutcome(null, null, null, null, null, "", Map.of());
        };
        SkillExecutionScopePort scopes = (tenant, user, skill, docs, tags) ->
            new SkillExecutionScopePort.EffectiveScope(List.of("requested-doc"), List.of(),
                List.of("analyst"), true, true);
        AgentRegistryPort registry = mock(AgentRegistryPort.class);
        AgentDescriptor provider = new AgentDescriptor("group.analysis", "v1", AgentDescriptor.Origin.GROUP,
            AgentDescriptor.Protocol.A2A_HTTP_JSON, URI.create("https://group.example/a2a"),
            Set.of(CapabilityId.parse("finance.analysis.v1")), AgentDescriptor.TrustLevel.GROUP_TRUSTED,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of("DocumentAnalysisEvidence",
                "ToolAnalysisEvidence"), null, "", 50, true,
            Map.of("allowedTenantIds", List.of("tenant-1"),
                "supportedExecutionModes", List.of("DOMAIN_INFERENCE")));
        when(registry.find("group.analysis")).thenReturn(Optional.of(provider));
        when(registry.list()).thenReturn(List.of(provider));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID)).thenReturn("tenant-1");
        when(request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID)).thenReturn("user-1");
        var controller = new AgentAnalysisController(runtime, scopes, registry);
        controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest("Analyze", "skill-1",
            "group.analysis", "finance.analysis.v1", List.of("requested-doc"), List.of(),
            List.of(new AgentAnalysisController.DomainToolCall("market_read", Map.of("symbol", "A"))),
            null, null, null, null, 2, 60_000L, true), request);
        assertThat(observed.get().attributes().get(AnalysisContext.DOMAIN_PROVIDER_ATTRIBUTE))
            .isEqualTo("group.analysis");
        assertThat(observed.get().attributes().get(AnalysisContext.AGENT_EXECUTION_MODE_ATTRIBUTE))
            .isEqualTo("DOMAIN_INFERENCE");
        assertThat(observed.get().intent().requiredCapabilities()).containsExactlyInAnyOrder(
            AnalysisCapability.DOCUMENT_SEARCH, AnalysisCapability.TOOL_CALL,
            AnalysisCapability.DOMAIN_INTELLIGENCE);
        assertThat(observed.get().documentIds()).containsExactly("requested-doc");
        assertThat(controller.domainProviders(request).getData()).hasSize(1);
        assertThat(controller.domainResources(new AgentAnalysisController.DomainSkillResourcesRequest(
            "skill-1", List.of(), List.of()), request).getData()).containsExactly("requested-doc");
        assertThatThrownBy(() -> controller.analyzeDomain(new AgentAnalysisController.DomainAnalyzeRequest(
            "Analyze", "skill-1", "group.analysis", "finance.analysis.v1", List.of("forbidden-doc"),
            List.of(), List.of(), null, null, null, null, 2, 60_000L, true), request))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("not authorized");
    }

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
