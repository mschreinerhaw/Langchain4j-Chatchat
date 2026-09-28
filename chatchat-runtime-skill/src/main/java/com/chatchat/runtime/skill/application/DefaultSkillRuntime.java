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
    private final SkillDataAcquisition data;
    private final SkillAnalysisExecutor analysis;

    public DefaultSkillRuntime(SkillRouter router, SkillResolver resolver,
                               WorkflowResolver workflows, AgentRuntimeDispatcher agents) {
        this(router, resolver, workflows, agents, new SkillDataAcquisition(java.util.List::of));
    }

    public DefaultSkillRuntime(SkillRouter router, SkillResolver resolver,
                               WorkflowResolver workflows, AgentRuntimeDispatcher agents, SkillDataAcquisition data) {
        this(router, resolver, workflows, agents, data, new SkillAnalysisExecutor(java.util.List::of));
    }

    public DefaultSkillRuntime(SkillRouter router, SkillResolver resolver, WorkflowResolver workflows,
                               AgentRuntimeDispatcher agents, SkillDataAcquisition data, SkillAnalysisExecutor analysis) {
        this.router = router;
        this.resolver = resolver;
        this.workflows = workflows;
        this.agents = agents;
        this.data = data;
        this.analysis = analysis;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        return execute(request, new com.chatchat.runtime.skill.api.execution.SkillDataSession());
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request, com.chatchat.runtime.skill.api.execution.SkillDataSession session) {
        var route = router.route(new SkillSearchRequest(request.query(), request.roleContext(),
            request.requestedSkillIds(), request.candidateLimit(), request.intent()));
        if (route.candidates().isEmpty())
            return new SkillExecutionResult("NO_AUTHORIZED_SKILL", route, null, null, null, Map.of());
        SkillResolution resolved = null;
        for (var descriptor : route.candidates()) {
            if (request.intent().get("skillVersion") instanceof String version && !version.equals(descriptor.version())) continue;
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
        Map<String, Object> attributes = new java.util.LinkedHashMap<>(request.attributes());
        // Callers cannot supply a supposedly acquired bundle.
        attributes.remove(SkillDataAcquisition.RESULTS);
        attributes.remove(SkillAnalysisExecutor.RESULTS);
        var requirements = resolved.skill().requirements().data();
        if (requirements.isEmpty() && Boolean.TRUE.equals(request.intent().get("dataContractsRequired")))
            return new SkillExecutionResult("NO_DATA_REQUIREMENTS", route, resolved, workflow, null, Map.of());
        if (!requirements.isEmpty()) {
            if (!java.util.Set.of("LANGCHAIN4J", "OPENAI_COMPATIBLE").contains(request.engine().toUpperCase(java.util.Locale.ROOT)))
                return new SkillExecutionResult("DATA_ANALYSIS_ENGINE_UNSUPPORTED", route, resolved, workflow, null, Map.of());
            Map<String, Object> inputs = new java.util.LinkedHashMap<>();
            if (attributes.get(SkillDataAcquisition.INPUTS) instanceof Map<?, ?> supplied)
                supplied.forEach((key, value) -> { if (key instanceof String name && value != null) inputs.put(name, value); });
            var datasets = data.acquire(resolved, request.roleContext(), inputs, session);
            attributes.put(SkillDataAcquisition.RESULTS, datasets);
            attributes.put(SkillAnalysisExecutor.RESULTS,
                Boolean.TRUE.equals(attributes.get("analysisBaseline")) ? java.util.List.of()
                    : analysis.execute(resolved.skill().requirements(), datasets, request.roleContext()));
        }
        var analysisSkill = resolved.skill();
        if (Boolean.TRUE.equals(attributes.get("analysisBaseline"))) {
            analysisSkill = new com.chatchat.runtime.skill.api.skill.ResolvedSkill(analysisSkill.descriptor(),
                "Summarize supplied data, cite provenance, and disclose missing inputs. Do not invent facts or computed metrics.",
                analysisSkill.resources(), analysisSkill.requirements(), analysisSkill.metadata());
        }
        RuntimeAgentExecutionResult execution = agents.execute(new RuntimeAgentExecutionRequest(
            request.engine(), request.query(), request.roleContext(), analysisSkill,
            resolved.authorizedScope(), workflow.workflow(), attributes));
        Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
        diagnostics.put("deterministicRouting", true);
        diagnostics.put("databaseAuthorization", true);
        diagnostics.put("analysisBaseline", Boolean.TRUE.equals(attributes.get("analysisBaseline")));
        if (attributes.containsKey(SkillDataAcquisition.RESULTS))
            diagnostics.put(SkillDataAcquisition.RESULTS, attributes.get(SkillDataAcquisition.RESULTS));
        if (attributes.containsKey(SkillAnalysisExecutor.RESULTS))
            diagnostics.put(SkillAnalysisExecutor.RESULTS, attributes.get(SkillAnalysisExecutor.RESULTS));
        return new SkillExecutionResult(execution.status(), route, resolved, workflow, execution,
            diagnostics);
    }
}
