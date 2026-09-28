package com.chatchat.integration.agent.skill;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.agent.AgentCardDiscoveryPort;
import com.chatchat.common.runtime.agent.AgentCredentialResolver;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentGatewayPort;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.capability.CapabilityId;
import com.chatchat.runtime.skill.spi.AgentRuntimeAdapter;
import com.chatchat.runtime.skill.spi.WorkflowResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Executes only database-registered and Skill-scope-authorized remote Agents. */
@Component
public class RegisteredExternalAgentExecutor {
    private final AgentRegistryPort registry;
    private final AgentGatewayPort gateway;
    private final AgentCardDiscoveryPort cards;
    private final AgentCredentialResolver credentials;

    public RegisteredExternalAgentExecutor(AgentRegistryPort registry, AgentGatewayPort gateway,
                                           AgentCardDiscoveryPort cards,
                                           ObjectProvider<AgentCredentialResolver> credentials) {
        this.registry = registry;
        this.gateway = gateway;
        this.cards = cards;
        this.credentials = credentials.getIfAvailable();
    }

    public AgentRuntimeAdapter.ExecutionResult execute(AgentRuntimeAdapter.ExecutionRequest request,
                                                        String expectedEngine,
                                                        Set<AgentDescriptor.Protocol> protocols) {
        Selection selected = select(request, expectedEngine, protocols);
        if (!selected.ready()) return failure(selected.status(), selected.details());
        AgentDescriptor agent = selected.agent();
        CapabilityId capability = capability(request, agent);
        if (capability == null) return failure("CAPABILITY_REQUIRED", Map.of("agentId", agent.agentId()));
        Long timeout = positiveLong(request.attributes().get("timeoutMs"), 60_000L);
        Integer attempts = positiveInt(request.attributes().get("maxAttempts"), 1);
        if (timeout == null || attempts == null)
            return failure("INVALID_RUNTIME_CONSTRAINT", Map.of(
                "agentId", agent.agentId(),
                "message", "timeoutMs and maxAttempts must be positive integers"));
        String executionId = text(request.attributes().get("runId"));
        if (executionId.isBlank()) executionId = UUID.randomUUID().toString();
        Map<String, Object> taskParameters = Map.of(
            "skillId", request.skill().descriptor().id(),
            "workflowId", request.workflow().workflowId());
        AgentExecutionRequest remote = new AgentExecutionRequest(null, executionId, capability,
            new AgentExecutionRequest.TaskContract(request.workflow().type().name(), request.query(), taskParameters),
            EvidenceBundle.empty("No evidence payload supplied by Skill Runtime"), Set.of(capability),
            new AgentExecutionRequest.Constraints(timeout, attempts, true, true, Set.of()), null,
            new KernelDataScope(request.roleContext().tenantId(), request.roleContext().userId(),
                textOrNull(request.attributes().get("requestId")),
                textOrNull(request.attributes().get("conversationId")), executionId,
                textOrNull(request.attributes().get("environment")), Map.of()),
            Map.of(AgentExecutionRequest.TARGET_AGENT_METADATA_KEY, agent.agentId()));
        AgentExecutionOutcome outcome = gateway.invoke(agent, remote);
        Map<String, Object> metadata = new LinkedHashMap<>(outcome.metadata());
        metadata.put("agentId", agent.agentId());
        metadata.put("agentProtocol", agent.protocol().name());
        metadata.put("executionId", outcome.executionId());
        if (!outcome.errorCode().isBlank()) metadata.put("errorCode", outcome.errorCode());
        if (!outcome.errorMessage().isBlank()) metadata.put("error", outcome.errorMessage());
        metadata.put("usage", outcome.usage());
        return new AgentRuntimeAdapter.ExecutionResult(outcome.status().name(), output(outcome), metadata);
    }

    public AgentRuntimeAdapter.HealthResult health(AgentRuntimeAdapter.HealthRequest request,
                                                    String expectedEngine,
                                                    Set<AgentDescriptor.Protocol> protocols) {
        String agentId = text(request == null ? null : request.attributes().get("agentId"));
        if (agentId.isBlank()) return healthFailure("AGENT_ID_REQUIRED", Map.of());
        AgentDescriptor agent = registry.find(agentId).orElse(null);
        Selection selected = validate(agent, agentId, expectedEngine, protocols);
        if (!selected.ready()) return healthFailure(selected.status(), selected.details());
        if (agent.protocol() != AgentDescriptor.Protocol.A2A_HTTP_JSON)
            return new AgentRuntimeAdapter.HealthResult("REGISTERED", Map.of(
                "agentId", agent.agentId(), "protocol", agent.protocol().name(),
                "endpoint", agent.endpoint().toString()));
        try {
            String token = token(agent);
            var card = cards.discoverSummary(agent, token);
            return new AgentRuntimeAdapter.HealthResult("READY", Map.of(
                "agentId", agent.agentId(), "protocol", agent.protocol().name(),
                "endpoint", card.endpoint(), "cardName", card.name(), "cardVersion", card.version(),
                "signatureVerified", card.signatureVerified()));
        } catch (RuntimeException error) {
            return healthFailure("UNAVAILABLE", Map.of(
                "agentId", agent.agentId(), "error", error.getClass().getSimpleName()));
        }
    }

    private Selection select(AgentRuntimeAdapter.ExecutionRequest request, String expectedEngine,
                             Set<AgentDescriptor.Protocol> protocols) {
        if (request == null || request.scope() == null || !request.scope().skillAllowed())
            return invalid("SKILL_NOT_AUTHORIZED", Map.of());
        if (request.roleContext() == null) return invalid("ROLE_CONTEXT_REQUIRED", Map.of());
        if (request.workflow() == null || request.workflow().type() != WorkflowResolver.WorkflowType.EXTERNAL_AGENT)
            return invalid("EXTERNAL_AGENT_WORKFLOW_REQUIRED", Map.of());
        String agentId = text(request.attributes().get("agentId"));
        if (agentId.isBlank() && request.scope().agentIds().size() == 1)
            agentId = request.scope().agentIds().get(0);
        if (agentId.isBlank()) return invalid("AGENT_SELECTION_REQUIRED",
            Map.of("authorizedAgentCount", request.scope().agentIds().size()));
        if (!request.scope().agentIds().contains(agentId))
            return invalid("AGENT_NOT_AUTHORIZED", Map.of("agentId", agentId));
        return validate(registry.find(agentId).orElse(null), agentId, expectedEngine, protocols);
    }

    private Selection validate(AgentDescriptor agent, String agentId, String expectedEngine,
                               Set<AgentDescriptor.Protocol> protocols) {
        if (agent == null) return invalid("AGENT_NOT_REGISTERED", Map.of("agentId", agentId));
        if (!agent.enabled()) return invalid("AGENT_DISABLED", Map.of("agentId", agentId));
        if (agent.origin() == AgentDescriptor.Origin.LOCAL)
            return invalid("REMOTE_AGENT_REQUIRED", Map.of("agentId", agentId));
        if (!protocols.contains(agent.protocol()))
            return invalid("AGENT_PROTOCOL_MISMATCH", Map.of(
                "agentId", agentId, "protocol", agent.protocol().name()));
        String declaredEngine = normalize(agent.metadata().get("runtimeEngine"));
        if (!normalize(expectedEngine).equals(declaredEngine))
            return invalid("AGENT_ENGINE_MISMATCH", Map.of(
                "agentId", agentId, "expectedEngine", expectedEngine,
                "declaredEngine", declaredEngine));
        return new Selection(true, "READY", agent, Map.of("agentId", agentId));
    }

    private CapabilityId capability(AgentRuntimeAdapter.ExecutionRequest request, AgentDescriptor agent) {
        String explicit = text(request.attributes().get("capabilityId"));
        if (!explicit.isBlank()) {
            CapabilityId value;
            try { value = CapabilityId.parse(explicit); }
            catch (IllegalArgumentException error) { return null; }
            return agent.capabilities().contains(value) ? value : null;
        }
        return agent.capabilities().size() == 1 ? agent.capabilities().iterator().next() : null;
    }

    private String token(AgentDescriptor agent) {
        if (agent.credentialRef().isBlank()) return null;
        if (credentials == null) throw new IllegalStateException("Agent credential resolver is unavailable");
        Optional<String> resolved = credentials.resolveBearerToken(agent.credentialRef());
        return resolved.orElseThrow(() -> new IllegalStateException("Agent credential reference cannot be resolved"));
    }

    private String output(AgentExecutionOutcome outcome) {
        List<String> claims = outcome.claims().stream().map(AgentExecutionOutcome.GroundedClaim::text)
            .filter(value -> value != null && !value.isBlank()).toList();
        if (!claims.isEmpty()) return String.join("\n", claims);
        return outcome.artifacts().stream().map(AgentExecutionOutcome.Artifact::content)
            .filter(value -> value != null && !value.isBlank()).reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private AgentRuntimeAdapter.ExecutionResult failure(String status, Map<String, Object> details) {
        return new AgentRuntimeAdapter.ExecutionResult(status, "", details);
    }
    private AgentRuntimeAdapter.HealthResult healthFailure(String status, Map<String, Object> details) {
        return new AgentRuntimeAdapter.HealthResult(status, details);
    }
    private Selection invalid(String status, Map<String, Object> details) {
        return new Selection(false, status, null, details);
    }
    private Integer positiveInt(Object value, int defaultValue) {
        if (value == null || text(value).isBlank()) return defaultValue;
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException error) { return null; }
    }
    private Long positiveLong(Object value, long defaultValue) {
        if (value == null || text(value).isBlank()) return defaultValue;
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(text(value));
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException error) { return null; }
    }
    private String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private String textOrNull(Object value) { String text = text(value); return text.isBlank() ? null : text; }
    private String normalize(Object value) { return text(value).toUpperCase(Locale.ROOT); }

    private record Selection(boolean ready, String status, AgentDescriptor agent,
                             Map<String, Object> details) { }
}
