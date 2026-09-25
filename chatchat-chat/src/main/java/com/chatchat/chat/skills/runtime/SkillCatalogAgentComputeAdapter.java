package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.agent.LocalAgentExecutionPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exposes existing SkillDefinition-backed agents as local domain compute without duplicating them. */
@Component
public class SkillCatalogAgentComputeAdapter implements LocalAgentExecutionPort, InitializingBean {
    public static final String CAPABILITIES_KEY = "agentCapabilities";
    private final SkillCatalogService skills;
    private final AgentRuntime runtime;
    private final AgentRegistryPort registry;

    public SkillCatalogAgentComputeAdapter(SkillCatalogService skills, AgentRuntime runtime,
                                           AgentRegistryPort registry) {
        this.skills = skills;
        this.runtime = runtime;
        this.registry = registry;
    }

    @Override public void afterPropertiesSet() {
        skills.list().forEach(skill -> registry.register(descriptor(skill)));
    }

    @Override public boolean supports(String agentId) {
        return agentId != null && skills.list().stream().anyMatch(skill -> agentId.equals(localId(skill.id())));
    }

    @Override public AgentExecutionOutcome execute(AgentDescriptor descriptor, AgentExecutionRequest request) {
        SkillDefinition skill = skills.resolve(skillId(descriptor.agentId()));
        AgentRunResult result = runtime.run(AgentRunRequest.builder()
            .runId(request.executionId())
            .query(request.task().instruction())
            .tenantId(request.scope().tenantId())
            .userId(request.scope().userId())
            .requestId(request.scope().requestId())
            .conversationId(request.scope().conversationId())
            .skillId(skill.id())
            .modelName(skill.modelName())
            .systemPrompt(skill.systemPrompt())
            .availableTools(skill.boundMcpToolNames())
            .boundDocumentIds(skill.boundDocumentIds())
            .boundDocumentTags(skill.boundDocumentTags())
            .timeoutMs(request.constraints().timeoutMs())
            .attributes(Map.of("federatedAgentExecution", true, "evidenceBundle", request.evidence(),
                "capability", request.capability().value()))
            .build());
        AgentExecutionOutcome.Status status = status(result.status());
        List<AgentExecutionOutcome.Artifact> artifacts = result.answer() == null || result.answer().isBlank()
            ? List.of() : List.of(new AgentExecutionOutcome.Artifact("answer", "text/markdown", result.answer(),
                Map.of("source", "local-agent-runtime")));
        return new AgentExecutionOutcome(AgentExecutionOutcome.SCHEMA_VERSION, request.executionId(),
            descriptor.agentId(), status, List.of(), artifacts, List.of(),
            status == AgentExecutionOutcome.Status.PARTIAL ? List.of("local agent returned unstructured output") : List.of(),
            status == AgentExecutionOutcome.Status.FAILED ? "LOCAL_AGENT_FAILED" : "", result.errorMessage(),
            Map.of(), result.metadata());
    }

    private AgentDescriptor descriptor(SkillDefinition skill) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillId", skill.id());
        if (skill.label() != null) metadata.put("label", skill.label());
        if (skill.marketStatus() != null) metadata.put("marketStatus", skill.marketStatus());
        return new AgentDescriptor(localId(skill.id()), "v1", AgentDescriptor.Origin.LOCAL,
            AgentDescriptor.Protocol.LOCAL, null, capabilities(skill), AgentDescriptor.TrustLevel.INTERNAL,
            AgentDescriptor.DataAccessMode.RUNTIME_MANAGED, Set.of(), Set.of(),
            AgentExecutionOutcome.SCHEMA_VERSION, "", 50, true,
            metadata);
    }

    private Set<CapabilityId> capabilities(SkillDefinition skill) {
        LinkedHashSet<CapabilityId> result = new LinkedHashSet<>();
        Object declared = skill.workflowConfig() == null ? null : skill.workflowConfig().get(CAPABILITIES_KEY);
        if (declared instanceof Iterable<?> values) {
            values.forEach(value -> { if (value != null) result.add(CapabilityId.parse(String.valueOf(value))); });
        }
        result.add(CapabilityId.parse("local." + skill.id().replace('_', '-') + ".v1"));
        return Set.copyOf(result);
    }

    private AgentExecutionOutcome.Status status(AgentRunStatus value) {
        return switch (value) {
            case COMPLETED -> AgentExecutionOutcome.Status.PARTIAL;
            case WAITING_CONFIRMATION -> AgentExecutionOutcome.Status.INPUT_REQUIRED;
            case CANCELLED -> AgentExecutionOutcome.Status.CANCELLED;
            case FAILED -> AgentExecutionOutcome.Status.FAILED;
            case PENDING, RUNNING -> AgentExecutionOutcome.Status.PARTIAL;
        };
    }

    private String localId(String skillId) { return "local.skill." + skillId; }
    private String skillId(String agentId) { return agentId.substring("local.skill.".length()); }
}
