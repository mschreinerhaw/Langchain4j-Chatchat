package com.chatchat.api.model;

import com.chatchat.common.runtime.agent.AgentDescriptor;
import com.chatchat.common.runtime.agent.AgentExecutionMode;
import com.chatchat.common.runtime.agent.AgentRegistryPort;
import com.chatchat.common.runtime.capability.CapabilityId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One discovery and admission surface; model and Agent execution adapters remain distinct. */
@Service
public class IntelligenceProviderRegistry {
    private final PlatformModelCatalogService models;
    private final AgentRegistryPort agents;

    public IntelligenceProviderRegistry(PlatformModelCatalogService models, AgentRegistryPort agents) {
        this.models = models;
        this.agents = agents;
    }

    public record Provider(String providerId, String displayName, String kind, String origin,
                           List<String> capabilities, List<String> evidenceTypes,
                           boolean grantRestricted, List<String> skillIds, List<String> documentIds,
                           List<String> mcpToolNames, boolean mcpRoleGoverned,
                           List<String> professionalCapabilities) { }

    public List<Provider> list(String tenantId) {
        List<Provider> result = new ArrayList<>();
        for (var model : models.list()) {
            if ("chat".equals(model.type()) && model.published() && model.activeEnabled())
                result.add(new Provider("llm:" + model.name(),
                    model.alias() == null || model.alias().isBlank() ? model.name() : model.alias(),
                    "GENERAL_LLM", "PLATFORM", List.of("general.analysis.v1"),
                    List.of("DocumentAnalysisEvidence", "ToolAnalysisEvidence", "StructuredDataEvidence"),
                    false, List.of(), List.of(), List.of(), false, List.of()));
        }
        for (AgentDescriptor agent : agents.list()) {
            if (!agent.enabled() || agent.origin() == AgentDescriptor.Origin.LOCAL
                || !agent.supportsExecutionMode(AgentExecutionMode.DOMAIN_INFERENCE)
                || !(agent.metadata().get("allowedTenantIds") instanceof Iterable<?> tenants)) continue;
            boolean admitted = false;
            for (Object tenant : tenants) if (tenantId.equals(tenant)) admitted = true;
            if (!admitted) continue;
            Object rawGrants = agent.metadata().get("analysisGrants");
            Map<?, ?> grants = rawGrants instanceof Map<?, ?> map ? map : Map.of();
            result.add(new Provider(agent.agentId(),
                String.valueOf(agent.metadata().getOrDefault("displayName", agent.agentId())),
                "DOMAIN_AGENT", agent.origin().name(),
                agent.capabilities().stream().map(CapabilityId::value).sorted().toList(),
                agent.allowedEvidenceTypes().stream().sorted().toList(),
                rawGrants instanceof Map<?, ?>,
                strings(grants.get("skillIds")), strings(grants.get("documentIds")),
                strings(grants.get("mcpToolNames")),
                Boolean.TRUE.equals(grants.get("mcpRoleGoverned")),
                strings(agent.metadata().get("professionalCapabilities"))));
        }
        return List.copyOf(result);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) if (item instanceof String text && !text.isBlank()) result.add(text);
        return List.copyOf(result);
    }

    public boolean admits(String tenantId, String providerId, String capability) {
        return list(tenantId).stream().anyMatch(provider -> provider.providerId().equals(providerId)
            && provider.capabilities().contains(capability));
    }
}
