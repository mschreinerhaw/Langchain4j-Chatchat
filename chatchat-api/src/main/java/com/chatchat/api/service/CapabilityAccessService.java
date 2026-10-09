package com.chatchat.api.service;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.api.runtime.EnterpriseToolRuntimePolicyProvider;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.mcp.capability.CapabilityManifest;
import com.chatchat.common.mcp.service.*;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.tool.*;
import com.chatchat.enterprise.service.CapabilitySnapshotStore;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Authenticated discovery is deliberately separate from argument-specific execution authorization. */
@Service
public class CapabilityAccessService {
    private final McpRuntimeAccessService mcp;
    private final CapabilitySnapshotStore snapshots;
    private final ToolRegistry tools;
    private final EnterpriseToolRuntimePolicyProvider policy;
    private final ResourceAuthorizationPort grants;
    private final SkillCatalogService agents;
    private final EnterpriseAdminService users;
    private final ToolRuntimeService executor;
    public CapabilityAccessService(McpRuntimeAccessService mcp, CapabilitySnapshotStore snapshots, ToolRegistry tools,
        EnterpriseToolRuntimePolicyProvider policy, ResourceAuthorizationPort grants, SkillCatalogService agents,
        EnterpriseAdminService users, ToolRuntimeService executor) {
        this.mcp = mcp; this.snapshots = snapshots; this.tools = tools; this.policy = policy;
        this.grants = grants; this.agents = agents; this.users = users; this.executor = executor;
    }
    public record Scope(String tenantId, String userId, String agentId) {
        public Scope {
            if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank())
                throw new RuntimeScopeAccessDeniedException("Authenticated capability discovery requires tenant and user");
        }
    }
    public record Summary(String capabilityId, String version, String name, String description,
                          String serviceId, String toolName, String status) { }
    public record Page(List<Summary> items, int total, int offset, int limit) { }
    public record Invocation(String version, Map<String, Object> arguments, String conversationId) { }

    public void synchronizeCatalog() { current(); }

    public Page discover(Scope scope, String query, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("offset >= 0; limit 1..100 required");
        SkillDefinition agent = agent(scope);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Summary> visible = current().stream().filter(manifest -> allowed(manifest, scope, agent))
            .filter(manifest -> searchable(manifest).contains(needle))
            .sorted(Comparator.comparing(CapabilityManifest::capabilityId))
            .map(manifest -> new Summary(manifest.capabilityId(), manifest.version(),
                text(manifest.discovery().get("name")), text(manifest.discovery().get("description")),
                text(manifest.provider().get("serverId")), text(manifest.provider().get("toolName")), manifest.status()))
            .toList();
        return new Page(visible.stream().skip(offset).limit(limit).toList(), visible.size(), offset, limit);
    }
    public CapabilityManifest detail(Scope scope, String id) {
        SkillDefinition agent = agent(scope);
        return current().stream().filter(manifest -> manifest.capabilityId().equals(id))
            .filter(manifest -> allowed(manifest, scope, agent)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Capability not available"));
    }
    public ToolRuntimeExecution invoke(Scope scope, String id, Invocation invocation) {
        if (invocation == null || invocation.version() == null || invocation.version().isBlank())
            throw new IllegalArgumentException("Current capability version is required");
        long executionRevision = tools.getRevision();
        CapabilityManifest manifest = detail(scope, id); // Re-read source and re-evaluate current grants.
        if (!manifest.version().equals(invocation.version()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capability contract changed; discover current version");
        ToolRuntimeRequest request = runtimeRequest(scope, text(manifest.provider().get("toolName")),
            invocation.arguments() == null ? Map.of() : invocation.arguments());
        request.setAllowedTools(List.of(request.getToolName()));
        request.setConversationId(invocation.conversationId());
        request.getToolInput().setConversationId(invocation.conversationId());
        request.getAttributes().put("toolRegistryRevision", executionRevision);
        // The existing runtime owns schema validation, confirmations, dynamic-child binding and MCP execution.
        return executor.execute(request);
    }
    private List<CapabilityManifest> current() {
        List<McpToolDescriptor> contracts = mcp.tools(new McpToolQuery(null, null, Set.of()));
        // Failure/partial refresh must not turn a transport outage into mass retirement.
        Set<String> authoritative = mcp.health().ready()
            ? mcp.services().stream().filter(McpServiceDescriptor::enabled).map(McpServiceDescriptor::serviceId)
                .collect(java.util.stream.Collectors.toSet()) : Set.of();
        return snapshots.synchronize(contracts, authoritative);
    }
    private SkillDefinition agent(Scope scope) {
        if (scope.agentId() == null || scope.agentId().isBlank()) return null;
        SkillDefinition agent = agents.resolve(scope.agentId());
        if (!scope.agentId().equals(agent.id()) || (!users.hasAllAgentAccess(scope.userId())
            && (!"published".equalsIgnoreCase(agent.marketStatus()) || !users.canAccessAgent(scope.userId(), agent.id()))))
            throw new RuntimeScopeAccessDeniedException("Agent is outside caller's authorized scope");
        return agent;
    }
    private boolean allowed(CapabilityManifest manifest, Scope scope, SkillDefinition agent) {
        if (!Set.of("PUBLISHED", "DEPRECATED").contains(manifest.status())) return false;
        String name = text(manifest.provider().get("toolName"));
        String service = text(manifest.provider().get("serverId"));
        ToolMetadata metadata = tools.getToolMetadata(name);
        if (metadata == null || !metadata.isAgentCompatible()) return false;
        if (metadata.getVisibleTenantIds() != null && !metadata.getVisibleTenantIds().isEmpty()
            && !metadata.getVisibleTenantIds().contains(scope.tenantId())) return false;
        if (agent != null && !bound(agent, service, name)) return false;
        ToolRuntimePolicy decision = policy.resolve(runtimeRequest(scope, name, Map.of()), metadata);
        if (decision != null) return Boolean.TRUE.equals(decision.allowed());
        // Unmanaged dynamic children have publisher-side execution ACLs. Do not inherit a parent's allow
        // or regard an absent API decision as authorization. Require an explicit platform grant here.
        return grants.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL, scope.tenantId(), scope.userId(),
            Set.of(), Set.of(name), scope.agentId()).contains(name);
    }
    private boolean bound(SkillDefinition agent, String service, String name) {
        if (agent.toolConfigs() != null && agent.toolConfigs().stream()
            .anyMatch(config -> name.equals(config.toolName()) && Boolean.FALSE.equals(config.enabled()))) return false;
        boolean explicitTools = (agent.boundMcpToolNames() != null && !agent.boundMcpToolNames().isEmpty())
            || (agent.toolConfigs() != null && !agent.toolConfigs().isEmpty());
        if (explicitTools) return (agent.boundMcpToolNames() != null && agent.boundMcpToolNames().contains(name))
            || (agent.toolConfigs() != null && agent.toolConfigs().stream()
                .anyMatch(config -> name.equals(config.toolName()) && !Boolean.FALSE.equals(config.enabled())));
        return agent.boundMcpServiceIds() != null && agent.boundMcpServiceIds().contains(service);
    }
    private ToolRuntimeRequest runtimeRequest(Scope scope, String name, Map<String, Object> arguments) {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", scope.tenantId()); context.put("userId", scope.userId());
        context.put("scopeAuthority", "API_AUTHENTICATION_CONTEXT");
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (scope.agentId() != null) attributes.put("authorizationAgentId", scope.agentId());
        return ToolRuntimeRequest.builder().tenantId(scope.tenantId()).userId(scope.userId()).toolName(name)
            .requestId(requestId).runtimeMode("agent").attributes(attributes)
            .toolInput(ToolInput.builder().userId(scope.userId()).requestId(requestId)
                .parameters(new LinkedHashMap<>(arguments)).context(context).build()).build();
    }
    private String searchable(CapabilityManifest manifest) {
        return (manifest.discovery().toString() + " " + manifest.publisherCapabilities()).toLowerCase(Locale.ROOT);
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
