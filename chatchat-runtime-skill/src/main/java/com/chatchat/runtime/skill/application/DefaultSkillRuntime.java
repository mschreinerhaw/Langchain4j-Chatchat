package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.execution.SkillExecutionRequest;
import com.chatchat.runtime.skill.api.execution.SkillExecutionResult;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionResult;
import com.chatchat.runtime.skill.api.resolution.SkillResolution;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import com.chatchat.runtime.skill.api.discovery.SkillSearchRequest;
import com.chatchat.runtime.skill.port.outbound.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.port.inbound.AgentRuntimeDispatcher;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.chatchat.runtime.skill.port.inbound.SkillRouter;
import com.chatchat.runtime.skill.port.inbound.SkillRuntime;
import com.chatchat.runtime.skill.port.inbound.WorkflowResolver;

import java.util.Map;

/** Complete deterministic route: metadata -> authorized content -> workflow -> engine adapter. */
public final class DefaultSkillRuntime implements SkillRuntime {
    private final SkillRouter router;
    private final SkillResolver resolver;
    private final WorkflowResolver workflows;
    private final AgentRuntimeDispatcher agents;

    public DefaultSkillRuntime(SkillRouter router, SkillResolver resolver,
                               WorkflowResolver workflows, AgentRuntimeDispatcher agents) {
        this.router = router;
        this.resolver = resolver;
        this.workflows = workflows;
        this.agents = agents;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        var route = router.route(new SkillSearchRequest(request.query(), request.roleContext(),
            request.requestedSkillIds(), request.candidateLimit(), request.intent()));
        if (route.candidates().isEmpty())
            return new SkillExecutionResult("NO_AUTHORIZED_SKILL", route, null, null, null, Map.of());
        SkillResolution resolved = null;
        for (var descriptor : route.candidates()) {
            SkillResolution candidate = resolver.resolve(new SkillResolutionRequest(
                descriptor.id(), descriptor.version(), request.roleContext()));
            if (candidate.resolved()) { resolved = candidate; break; }
        }
        if (resolved == null)
            return new SkillExecutionResult("SKILL_RESOLUTION_FAILED", route, null, null, null, Map.of());
        var workflow = workflows.resolve(resolved.skill(), resolved.authorizedScope(),
            request.roleContext(), request.intent());
        if (!workflow.resolved())
            return new SkillExecutionResult(workflow.status(), route, resolved, workflow, null, Map.of());
        RuntimeAgentExecutionResult execution = agents.execute(new RuntimeAgentExecutionRequest(
            request.engine(), request.query(), request.roleContext(), resolved.skill(),
            resolved.authorizedScope(), workflow.workflow(), request.attributes()));
        return new SkillExecutionResult(execution.status(), route, resolved, workflow, execution,
            Map.of("deterministicRouting", true, "databaseAuthorization", true));
    }
}
