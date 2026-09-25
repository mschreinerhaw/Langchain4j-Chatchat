package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentComputeRuntimePort;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentGatewayPort;
import com.chatchat.common.runtime.agent.AgentProvider;
import com.chatchat.common.runtime.agent.LocalAgentExecutionPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Capability-driven execution across local and remote agent compute providers. */
@Service
public class DefaultAgentComputeRuntime implements AgentComputeRuntimePort {
    private final AgentCapabilityPlanner planner;
    private final AgentOutcomeVerifier verifier;
    private final AgentGatewayPort gateway;
    private final RemoteAgentEvidenceProjector projector;
    private final Map<String, AgentProvider> localProviders;
    private final ObjectProvider<LocalAgentExecutionPort> localAdapters;

    public DefaultAgentComputeRuntime(AgentCapabilityPlanner planner,
                                      AgentOutcomeVerifier verifier,
                                      ObjectProvider<AgentGatewayPort> gateway,
                                      List<AgentProvider> providers,
                                      ObjectProvider<LocalAgentExecutionPort> localAdapters,
                                      RemoteAgentEvidenceProjector projector) {
        this.planner = planner;
        this.verifier = verifier;
        this.gateway = gateway.getIfAvailable();
        Map<String, AgentProvider> indexed = new LinkedHashMap<>();
        if (providers != null) providers.forEach(provider -> indexed.put(provider.descriptor().agentId(), provider));
        this.localProviders = Map.copyOf(indexed);
        this.localAdapters = localAdapters;
        this.projector = projector;
    }

    @Override
    public AgentExecutionOutcome execute(AgentExecutionRequest request) {
        if (request == null) throw new IllegalArgumentException("agent execution request is required");
        List<AgentDescriptor> candidates = planner.candidates(request);
        if (candidates.isEmpty()) return failure(request, "", AgentExecutionOutcome.Status.BLOCKED,
            "AGENT_CAPABILITY_UNAVAILABLE", "No policy-admitted agent provides " + request.capability());

        AgentExecutionOutcome lastFailure = null;
        for (AgentDescriptor candidate : candidates) {
            AgentExecutionRequest effective;
            try {
                effective = candidate.origin() == AgentDescriptor.Origin.LOCAL
                    ? request : projector.project(request);
            } catch (IllegalArgumentException invalidProjection) {
                lastFailure = failure(request, candidate.agentId(), AgentExecutionOutcome.Status.BLOCKED,
                    "AGENT_PROJECTION_REJECTED", "Remote evidence projection was rejected");
                continue;
            }
            AgentExecutionOutcome outcome = invoke(candidate, effective);
            AgentOutcomeVerifier.Verification verification = verifier.verify(candidate, effective, outcome);
            if (!verification.accepted()) {
                lastFailure = failure(request, candidate.agentId(), AgentExecutionOutcome.Status.FAILED,
                    verification.code(), verification.message());
                continue;
            }
            if (outcome.status() == AgentExecutionOutcome.Status.FAILED
                || outcome.status() == AgentExecutionOutcome.Status.TIMED_OUT
                || outcome.status() == AgentExecutionOutcome.Status.BLOCKED
                || outcome.status() == AgentExecutionOutcome.Status.CANCELLED
                || (outcome.status() == AgentExecutionOutcome.Status.PARTIAL && outcome.claims().isEmpty())) {
                lastFailure = outcome;
                continue;
            }
            return outcome;
        }
        return lastFailure == null ? failure(request, "", AgentExecutionOutcome.Status.FAILED,
            "AGENT_EXECUTION_FAILED", "All admitted agent providers failed") : lastFailure;
    }

    private AgentExecutionOutcome invoke(AgentDescriptor candidate, AgentExecutionRequest request) {
        try {
            if (candidate.origin() == AgentDescriptor.Origin.LOCAL) {
                AgentProvider provider = localProviders.get(candidate.agentId());
                if (provider != null && provider.available(request)) return provider.execute(request);
                return localAdapters.orderedStream().filter(adapter -> adapter.supports(candidate.agentId())).findFirst()
                    .map(adapter -> adapter.execute(candidate, request))
                    .orElseGet(() -> failure(request, candidate.agentId(), AgentExecutionOutcome.Status.FAILED,
                        "LOCAL_AGENT_UNAVAILABLE", "Local provider is unavailable"));
            }
            if (gateway == null) return failure(request, candidate.agentId(), AgentExecutionOutcome.Status.FAILED,
                "AGENT_GATEWAY_UNAVAILABLE", "Remote agent gateway is not configured");
            return gateway.invoke(candidate, request);
        } catch (RuntimeException error) {
            return failure(request, candidate.agentId(), AgentExecutionOutcome.Status.FAILED,
                "AGENT_PROVIDER_FAILURE", safeMessage(error));
        }
    }

    private AgentExecutionOutcome failure(AgentExecutionRequest request, String provider,
                                          AgentExecutionOutcome.Status status, String code, String message) {
        return new AgentExecutionOutcome(AgentExecutionOutcome.SCHEMA_VERSION, request.executionId(), provider,
            status, List.of(), List.of(), List.of(), List.of(), code, message, Map.of(), Map.of());
    }

    private String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName() : error.getMessage();
    }
}
