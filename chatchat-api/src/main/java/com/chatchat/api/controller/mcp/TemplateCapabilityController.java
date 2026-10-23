package com.chatchat.api.controller.mcp;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.chat.skills.model.SkillToolConfig;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.retrieval.ResourceGrantProvisioningPort;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.TemplateToolNames;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.api.service.McpRuntimeAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Business workflows share the existing publishing workspace and central identity authority. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-publications/capabilities")
public class TemplateCapabilityController {
    private final McpControlPlaneClient client;
    private final ObjectAccessService access;
    private final WorkspaceService workspaces;
    private final EnterpriseAdminService identities;
    private final SkillCatalogService agents;
    private final ObjectMapper json;
    private final ResourceGrantProvisioningPort ownerGrants;
    private final McpRuntimeAccessService runtime;
    private final SysAuditLogRepository audits;

    @GetMapping("/templates") public ApiResponse<JsonNode> templates(HttpServletRequest request) {
        authorize(request, "mcp:publication:read");
        return ApiResponse.success(client.request("GET", "/api/v1/template-capabilities/templates" + scope(request), null));
    }
    @GetMapping("/permission-catalog") public ApiResponse<JsonNode> permissionCatalog(HttpServletRequest request) {
        if (access.user(request) == null || !identities.hasPermission(access.user(request), "system:resource-grants:manage")) throw new SecurityException("Template permission catalog denied");
        workspaces.requireAvailable(request, "permissions");
        return ApiResponse.success(client.request("GET", "/api/v1/template-capabilities/templates" + scope(request) + "&management=true", null));
    }
    @GetMapping("/publications") public ApiResponse<JsonNode> publications(HttpServletRequest request) {
        authorize(request, "mcp:publication:read");
        return ApiResponse.success(client.request("GET", "/api/v1/template-capabilities/publications" + scope(request), null));
    }
    @PostMapping("/publications") public ApiResponse<JsonNode> publish(HttpServletRequest request, @RequestBody JsonNode input) {
        authorize(request, "mcp:publication:publish");
        var saved = client.request("POST", "/api/v1/template-capabilities/publications" + scope(request), input);
        String name = TemplateToolNames.local(saved.path("toolName").asText());
        ownerGrants.ensureUserOwnerGrant(ResourceAuthorizationPort.MCP_TOOL, access.tenant(request), name, access.user(request));
        audit(request, "template-publish", saved.path("id").asText()); runtime.refresh();
        return ApiResponse.success(saved);
    }
    @PostMapping("/publications/{id}/offline") public ApiResponse<JsonNode> offline(HttpServletRequest request, @PathVariable("id") String id) {
        authorize(request, "mcp:publication:publish");
        if (!id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid publication ID");
        var result = client.request("POST", "/api/v1/template-capabilities/publications/" + id + "/offline" + scope(request), null);
        audit(request, "template-offline", id); runtime.refresh(); return ApiResponse.success(result);
    }
    @GetMapping("/agents") public ApiResponse<List<Map<String, String>>> agentCatalog(HttpServletRequest request) {
        if (access.user(request) == null || !identities.hasPermission(access.user(request), "platform:agents:workshop")) throw new SecurityException("Agent assignment denied");
        return ApiResponse.success(agents.list().stream()
            .filter(agent -> identities.hasAllAgentAccess(access.user(request)) || identities.canAccessAgent(access.user(request), agent.id()))
            .map(agent -> Map.of("id", agent.id(), "label", Objects.toString(agent.label(), agent.id()), "marketStatus", Objects.toString(agent.marketStatus(), "draft"))).toList());
    }
    @GetMapping("/agents/{id}/tools") public ApiResponse<List<String>> agentTools(HttpServletRequest request, @PathVariable("id") String id) {
        var agent = requireAgent(request, id);
        return ApiResponse.success(agent.boundMcpToolNames() == null ? List.of() : agent.boundMcpToolNames().stream().filter(name -> TemplateToolNames.remote(name) != null).toList());
    }
    @PutMapping("/agents/{id}/tools/{name}") public ApiResponse<List<String>> bind(HttpServletRequest request, @PathVariable("id") String id, @PathVariable("name") String name) {
        var agent = requireAgent(request, id);
        var publications = client.request("GET", "/api/v1/template-capabilities/publications" + scope(request), null);
        JsonNode published = java.util.stream.StreamSupport.stream(publications.spliterator(), false)
            .filter(row -> name.equals(row.path("toolName").asText()) && "RUNNING".equals(row.path("state").asText()))
            .findFirst().orElseThrow(() -> new SecurityException("Only an authorized running publication may be assigned"));
        var templates = client.request("GET", "/api/v1/template-capabilities/templates" + scope(request), null);
        if (java.util.stream.StreamSupport.stream(templates.spliterator(), false).noneMatch(row -> published.path("revision").asText().equals(row.path("revision").asText())))
            throw new SecurityException("Template authorization or version has changed");
        saveBinding(agent, name, true); audit(request, "agent-template-bind", id + ":" + name); return agentTools(request, id);
    }
    @DeleteMapping("/agents/{id}/tools/{name}") public ApiResponse<List<String>> unbind(HttpServletRequest request, @PathVariable("id") String id, @PathVariable("name") String name) {
        var agent = requireAgent(request, id);
        saveBinding(agent, name, false); audit(request, "agent-template-unbind", id + ":" + name); return agentTools(request, id);
    }
    private synchronized void saveBinding(SkillDefinition agent, String name, boolean enabled) {
        String local = TemplateToolNames.local(name);
        var current = agents.resolve(agent.id());
        Set<String> names = new LinkedHashSet<>(current.boundMcpToolNames() == null ? List.of() : current.boundMcpToolNames());
        names.removeIf(value -> Objects.equals(TemplateToolNames.remote(value), TemplateToolNames.remote(local)));
        if (enabled) names.add(local);
        List<SkillToolConfig> configs = new ArrayList<>(current.toolConfigs() == null ? List.of() : current.toolConfigs());
        configs.removeIf(value -> Objects.equals(TemplateToolNames.remote(value.toolName()), TemplateToolNames.remote(local)));
        configs.add(new SkillToolConfig(local, null, "chatchat-mcp-server", null, List.of(), null, 1, enabled));
        Map<String, Object> value = json.convertValue(current, new com.fasterxml.jackson.core.type.TypeReference<>(){});
        value.put("boundMcpToolNames", names); value.put("toolConfigs", configs);
        agents.upsert(json.convertValue(value, SkillDefinition.class));
    }
    private SkillDefinition requireAgent(HttpServletRequest request, String id) {
        if (access.user(request) == null || !identities.hasPermission(access.user(request), "platform:agents:workshop")
            || !(identities.hasAllAgentAccess(access.user(request)) || identities.canAccessAgent(access.user(request), id))) throw new SecurityException("Agent assignment denied");
        var agent = agents.resolve(id);
        if (!id.equals(agent.id())) throw new SecurityException("Unknown Agent");
        return agent;
    }
    private void authorize(HttpServletRequest request, String permission) {
        workspaces.requireAvailable(request, "mcp-publication");
        if (access.user(request) == null || !identities.hasPermission(access.user(request), permission)) throw new SecurityException("Template publication denied");
    }
    private String scope(HttpServletRequest request) { return "?tenantId=" + encode(access.tenant(request)) + "&actorId=" + encode(access.user(request)); }
    private String encode(String value) { return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8); }
    private void audit(HttpServletRequest request, String action, String id) {
        var log = new SysAuditLog(); log.setTenantId(access.tenant(request)); log.setActorId(access.user(request));
        log.setModuleName("template-capability"); log.setActionName(action); log.setResourceType("template-tool"); log.setResourceId(id); audits.saveAndFlush(log);
    }
}
