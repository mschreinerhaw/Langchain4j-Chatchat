package com.chatchat.agents.runtime.federation;

import com.chatchat.common.runtime.agent.AgentComputeRuntimePort;
import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionOutcome;
import com.chatchat.common.runtime.agent.AgentExecutionRequest;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
import com.chatchat.common.runtime.agent.AgentToolRequest;
import com.chatchat.common.runtime.agent.AgentGatewayPort;
import com.chatchat.common.runtime.agent.AgentProvider;
import com.chatchat.common.runtime.agent.AgentEvidenceSupplementPort;
import com.chatchat.common.runtime.agent.LocalAgentExecutionPort;
import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Capability-driven execution across local and remote agent compute providers. */
@Service
public class DefaultAgentComputeRuntime implements AgentComputeRuntimePort {
    private final AgentCapabilityPlanner planner;
    private final AgentOutcomeVerifier verifier;
    private final AgentGatewayPort gateway;
    private final RemoteAgentEvidenceProjector projector;
    private final Map<String, AgentProvider> localProviders;
    private final ObjectProvider<LocalAgentExecutionPort> localAdapters;
    private final AgentEvidenceSupplementPort supplement;
    private final AgentHealthTracker health;

    @Autowired
    public DefaultAgentComputeRuntime(AgentCapabilityPlanner planner,
                                      AgentOutcomeVerifier verifier,
                                      ObjectProvider<AgentGatewayPort> gateway,
                                      List<AgentProvider> providers,
                                      ObjectProvider<LocalAgentExecutionPort> localAdapters,
                                      RemoteAgentEvidenceProjector projector,
                                      AgentEvidenceSupplementPort supplement,
                                      AgentHealthTracker health) {
        this.planner = planner;
        this.verifier = verifier;
        this.gateway = gateway.getIfAvailable();
        Map<String, AgentProvider> indexed = new LinkedHashMap<>();
        if (providers != null) providers.forEach(provider -> indexed.put(provider.descriptor().agentId(), provider));
        this.localProviders = Map.copyOf(indexed);
        this.localAdapters = localAdapters;
        this.projector = projector;
        this.supplement = supplement;
        this.health = health;
    }

    DefaultAgentComputeRuntime(AgentCapabilityPlanner planner, AgentOutcomeVerifier verifier,
                               ObjectProvider<AgentGatewayPort> gateway, List<AgentProvider> providers,
                               ObjectProvider<LocalAgentExecutionPort> localAdapters,
                               RemoteAgentEvidenceProjector projector) {
        this(planner, verifier, gateway, providers, localAdapters, projector, null, new AgentHealthTracker());
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
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.constraints().timeoutMs());
            long started = System.nanoTime();
            AgentExecutionOutcome outcome = invoke(candidate, effective);
            int attempts = 1;
            Set<String> requested = new LinkedHashSet<>();
            while (supplement != null
                && (outcome.status() == AgentExecutionOutcome.Status.INPUT_REQUIRED
                    || outcome.status() == AgentExecutionOutcome.Status.SUPPLEMENT_EVIDENCE)
                && attempts < Math.min(4, Math.min(request.constraints().maxAttempts(),
                    configuredAttempts(candidate)))
                && System.nanoTime() < deadline) {
                List<com.chatchat.common.runtime.analysis.plan.EvidenceRequirement> requirements;
                try { requirements = supplementRequirements(request, outcome); }
                catch (IllegalArgumentException rejected) {
                    outcome = failure(request, candidate.agentId(), AgentExecutionOutcome.Status.BLOCKED,
                        "AGENT_TOOL_REQUEST_REJECTED", rejected.getMessage());
                    break;
                }
                if (requirements.isEmpty()) break;
                List<AnalysisEvidence> added = new ArrayList<>();
                for (var requirement : requirements) {
                    if (requirement.type() == null || !requested.add(requirement.type())) continue;
                    try { added.addAll(supplement.supplement(candidate, request, requirement)); }
                    catch (RuntimeException ignored) { /* a failed local skill cannot expand remote permissions */ }
                }
                if (added.isEmpty()) break;
                List<AnalysisEvidence> combined = new ArrayList<>(effective.evidence().evidence());
                Set<String> existing = new LinkedHashSet<>();
                combined.forEach(value -> existing.add(value.evidenceId()));
                added.stream().filter(value -> existing.add(value.evidenceId())).forEach(combined::add);
                if (combined.size() == effective.evidence().evidence().size()) break;
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) break;
                var constraints = effective.constraints();
                AgentExecutionRequest merged = new AgentExecutionRequest(null, effective.executionId(),
                    effective.capability(), effective.task(), new EvidenceBundle(null, combined,
                    effective.evidence().limitations(), effective.evidence().metadata()),
                    effective.capabilityGrants(), new AgentExecutionRequest.Constraints(remaining,
                    constraints.maxAttempts(), constraints.citeEvidence(), constraints.rejectUnsupportedClaims(),
                    constraints.allowedDataDomains()), effective.outputContract(), effective.scope(),
                    effective.metadata());
                if (candidate.origin() == AgentDescriptor.Origin.LOCAL) effective = merged;
                else {
                    try { effective = projector.project(merged); }
                    catch (IllegalArgumentException rejected) { break; }
                    if (!candidate.allowedEvidenceTypes().containsAll(effective.evidence().evidence().stream()
                        .map(value -> value.attributes().getOrDefault("sourceType", value.getClass().getSimpleName()).toString()).toList())) break;
                }
                attempts++;
                try { outcome = candidate.origin() == AgentDescriptor.Origin.LOCAL
                    ? invoke(candidate, effective) : gateway.resume(candidate, effective); }
                catch (RuntimeException unsupported) { break; }
            }
            AgentOutcomeVerifier.Verification verification = verifier.verify(candidate, effective, outcome);
            health.record(candidate, verification.accepted() ? outcome : null,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (!verification.accepted()) {
                lastFailure = failure(request, candidate.agentId(), AgentExecutionOutcome.Status.FAILED,
                    verification.code(), verification.message());
                continue;
            }
            if (outcome.status() == AgentExecutionOutcome.Status.FAILED
                || outcome.status() == AgentExecutionOutcome.Status.TIMED_OUT
                || outcome.status() == AgentExecutionOutcome.Status.BLOCKED
                || outcome.status() == AgentExecutionOutcome.Status.CANCELLED
                || outcome.status() == AgentExecutionOutcome.Status.REPLAN_REQUIRED
                || (outcome.status() == AgentExecutionOutcome.Status.PARTIAL && outcome.claims().isEmpty())) {
                lastFailure = outcome;
                continue;
            }
            if (outcome.successful()) {
                Map<String, Object> runtimeMetadata = new LinkedHashMap<>(outcome.metadata());
                runtimeMetadata.put("runtimeEvidenceBundle", effective.evidence());
                return new AgentExecutionOutcome(null, outcome.executionId(), outcome.providerAgentId(),
                    outcome.status(), outcome.claims(), outcome.artifacts(), outcome.missingEvidence(),
                    outcome.limitations(), outcome.errorCode(), outcome.errorMessage(), outcome.usage(),
                    runtimeMetadata);
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

    private int configuredAttempts(AgentDescriptor candidate) {
        Object value = candidate.metadata().get("supplementMaxAttempts");
        return value instanceof Number number ? Math.max(1, number.intValue()) : 2;
    }

    private List<com.chatchat.common.runtime.analysis.plan.EvidenceRequirement> supplementRequirements(
        AgentExecutionRequest request, AgentExecutionOutcome outcome) {
        if (request.executionMode() != AgentExecutionMode.AGENTIC_EXECUTION)
            return outcome.missingEvidence(); // Existing evidence-only compatibility path.
        Object raw = outcome.metadata().get("toolRequests");
        if (!(raw instanceof List<?> items)) return List.of();
        if (items.size() > 3) throw new IllegalArgumentException("At most three tool requests are allowed per turn");
        Set<String> ids = new LinkedHashSet<>();
        List<com.chatchat.common.runtime.analysis.plan.EvidenceRequirement> requirements = new ArrayList<>();
        for (Object item : items) {
            AgentToolRequest tool = AgentToolRequest.from(item);
            if (!ids.add(tool.requestId())) throw new IllegalArgumentException("Duplicate tool requestId");
            requirements.add(tool.requirement());
        }
        return requirements;
    }
}
