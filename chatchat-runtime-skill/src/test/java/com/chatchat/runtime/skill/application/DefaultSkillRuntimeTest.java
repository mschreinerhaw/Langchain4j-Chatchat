package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.workflow.ResolvedWorkflow;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.api.execution.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.resource.SkillResourceRequest;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.discovery.SkillRouteResult;
import com.chatchat.runtime.skill.api.workflow.WorkflowResolution;
import com.chatchat.runtime.skill.api.workflow.WorkflowType;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.chatchat.runtime.skill.port.inbound.WorkflowResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSkillRuntimeTest {
    @Test
    void dataAcquisitionFailureStillReachesAnalysisAndReplacesCallerSuppliedResults() {
        var descriptor = descriptor("domain");
        var requirement = new com.chatchat.runtime.skill.api.skill.SkillDataRequirement("returns",
            "customer.returns.v1", List.of("describe"), false, Map.of());
        var skill = new ResolvedSkill(descriptor, "Analyze supplied data", List.of(),
            new com.chatchat.runtime.skill.api.skill.SkillRequirements(List.of(), List.of(), List.of(), List.of(),
                List.of("workflow"), List.of(requirement)), Map.of());
        var scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of("workflow"), List.of());
        var role = new SkillRoleContext("tenant", "user", List.of(), List.of(), Map.of());
        var runtime = new DefaultSkillRuntime(
            request -> new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()),
            resolver(new SkillResolution(skill, scope, "db", "RESOLVED", Map.of())), new DefaultWorkflowResolver(),
            request -> {
                assertThat(request.attributes().get(SkillDataAcquisition.RESULTS)).isInstanceOf(List.class);
                var results = (List<?>) request.attributes().get(SkillDataAcquisition.RESULTS);
                assertThat(results).hasSize(1);
                assertThat(((com.chatchat.runtime.skill.api.execution.SkillDataResult) results.get(0)).status())
                    .isEqualTo(com.chatchat.runtime.skill.api.execution.SkillDataResult.Status.NO_BINDING);
                return new RuntimeAgentExecutionResult("COMPLETED", "Data unavailable; review required", Map.of());
            });
        var result = runtime.execute(new SkillExecutionRequest("analyze", role, List.of("domain"), 1, "LANGCHAIN4J",
            Map.of("workflowType", "DATA_ANALYSIS", "workflowId", "workflow"),
            Map.of(SkillDataAcquisition.RESULTS, "forged success")));
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.diagnostics()).containsKey(SkillDataAcquisition.RESULTS);
    }


    @Test
    void executesOnlyResolvedSkillWorkflowAndDatabaseAuthorizedScope() {
        SkillDescriptor descriptor = descriptor("install-skill");
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
        AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of("document-1"), List.of(),
            List.of("mcp-1"), List.of(), List.of("install-workflow"), List.of());
        ResolvedSkill skill = new ResolvedSkill(descriptor, "Follow the installation procedure.",
            List.of(), null, Map.of());

        var runtime = new DefaultSkillRuntime(
            request -> new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()),
            resolver(new SkillResolution(skill, scope, "database", "RESOLVED", Map.of())),
            (resolved, authorized, context, intent) -> new WorkflowResolution(
                new ResolvedWorkflow("install-workflow",
                    WorkflowType.DOCUMENT, List.of("document-retrieval"),
                    Map.of("documentIds", authorized.documentIds(), "mcpToolIds", authorized.mcpToolIds())),
                "RESOLVED", Map.of()),
            request -> {
                assertThat(request.scope()).isSameAs(scope);
                assertThat(request.workflow().workflowId()).isEqualTo("install-workflow");
                assertThat(request.engine()).isEqualTo("LANGCHAIN4J");
                return new RuntimeAgentExecutionResult("COMPLETED", "done", Map.of());
            });

        var result = runtime.execute(new SkillExecutionRequest("install", role, List.of(), 5,
            "LANGCHAIN4J", Map.of("workflowType", "DOCUMENT"), Map.of()));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.diagnostics()).containsEntry("deterministicRouting", true)
            .containsEntry("databaseAuthorization", true);
    }

    @Test
    void stopsBeforeWorkflowAndEngineWhenNoAuthorizedSkillExists() {
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
        var runtime = new DefaultSkillRuntime(
            request -> new SkillRouteResult(List.of(), "NO_AUTHORIZED_CANDIDATES", Map.of()),
            resolver(null),
            (skill, scope, context, intent) -> { throw new AssertionError("workflow must not run"); },
            request -> { throw new AssertionError("engine must not run"); });

        var result = runtime.execute(new SkillExecutionRequest("install", role, List.of(), 5,
            "LANGCHAIN4J", Map.of(), Map.of()));

        assertThat(result.status()).isEqualTo("NO_AUTHORIZED_SKILL");
        assertThat(result.resolution()).isNull();
        assertThat(result.execution()).isNull();
    }

    private SkillResolver resolver(SkillResolution resolution) {
        return new SkillResolver() {
            @Override
            public SkillResolution resolve(com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest request) {
                return resolution;
            }

            @Override
            public Optional<com.chatchat.runtime.skill.api.resource.SkillResourceContent> readResource(
                SkillResourceRequest request) {
                return Optional.empty();
            }
        };
    }

    private SkillDescriptor descriptor(String id) {
        return new SkillDescriptor(id, "1", "Install", "", "operations", "DATABASE", "source",
            "", "", 1D, Map.of());
    }
}
