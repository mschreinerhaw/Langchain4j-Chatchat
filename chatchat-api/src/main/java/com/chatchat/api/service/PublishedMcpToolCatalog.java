package com.chatchat.api.service;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared business directory for tool discovery and Agent selection. Registration alone is insufficient. */
@Service @RequiredArgsConstructor
public class PublishedMcpToolCatalog {
    private final McpControlPlaneClient client;
    private final McpToolCatalogQueryPort catalog;
    private final ResourceAuthorizationPort authorization;
    private final ObjectMapper json;
    public record Publication(String title, String description, String category, int templateCount) { }

    public Map<String, Publication> publications(HttpServletRequest request) {
        Object principal = request == null ? null : request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_VIEW);
        if (!(principal instanceof EnterpriseAdminService.UserView user)) return Map.of();
        Set<String> roleIds = user.roleIds() == null ? Set.of() : Set.copyOf(user.roleIds());
        boolean manager = user.permissionCodes() != null && user.permissionCodes().contains("system:permission-center:manage");
        String path = "/api/v1/tool-publications?tenantId=" + encode(user.tenantId()) + "&actorId=" + encode(user.id());
        Map<String, Publication> candidates = new LinkedHashMap<>();
        for (JsonNode row : client.request("GET", path, null)) {
            if (!"RUNNING".equals(row.path("state").asText()) || !"CONTROLLED_TEMPLATE".equals(row.path("kind").asText())) continue;
            JsonNode definition;
            try { definition = json.readTree(row.path("definitionJson").asText()); }
            catch (Exception failure) { throw new IllegalStateException("Invalid published tool definition", failure); }
            if (!manager && !roleIds.contains(definition.path("roleId").asText())) continue;
            String domain = definition.path("domainCode").asText();
            if (!domain.matches("[a-z][a-z0-9_]{0,63}")) continue;
            String name = "mcp_chatchat_mcp_server_" + domain + "_template_query";
            String category = definition.path("businessCategory").asText("").trim();
            candidates.putIfAbsent(name, new Publication(row.path("title").asText(),
                row.path("description").asText("").isBlank() ? "在授权范围内查询已选模板" : row.path("description").asText(),
                category.isBlank() ? "待分类" : category, definition.path("templateKeys").size()));
        }
        Set<String> allowed = authorization.allowedIds(ResourceAuthorizationPort.MCP_TOOL, user.tenantId(), user.id(), roleIds, candidates.keySet());
        candidates.keySet().retainAll(allowed);
        return Collections.unmodifiableMap(candidates);
    }
    public List<McpToolCatalogQueryPort.RegisteredTool> registeredTools(HttpServletRequest request) {
        Map<String, Publication> published = publications(request);
        return catalog.registeredTools().stream().filter(tool -> published.containsKey(tool.localToolName()))
            .map(tool -> {
                Publication item = published.get(tool.localToolName());
                return new McpToolCatalogQueryPort.RegisteredTool(tool.localToolName(), tool.serviceId(), tool.serviceName(),
                    tool.remoteToolName(), item.description(), tool.backendServiceType(), item.category(), List.of(item.category()), List.of(),
                    Map.of("summary", "包含 " + item.templateCount() + " 个已选模板", "templateCount", item.templateCount(), "published", true),
                    tool.capabilityNode(), item.title());
            }).toList();
    }
    private String encode(String value) { return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8); }
}
