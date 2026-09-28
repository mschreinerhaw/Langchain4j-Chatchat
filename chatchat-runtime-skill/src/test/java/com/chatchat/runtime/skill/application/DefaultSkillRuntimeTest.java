package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.ResolvedWorkflow;
import com.chatchat.runtime.skill.api.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.SkillResolution;
import com.chatchat.runtime.skill.api.SkillResourceRequest;
import com.chatchat.runtime.skill.api.SkillRoleContext;
import com.chatchat.runtime.skill.api.SkillRouteResult;
import com.chatchat.runtime.skill.api.WorkflowResolution;
import com.chatchat.runtime.skill.api.WorkflowType;
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
            public SkillResolution resolve(com.chatchat.runtime.skill.api.SkillResolutionRequest request) {
                return resolution;
            }

            @Override
            public Optional<com.chatchat.runtime.skill.api.SkillResourceContent> readResource(
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
