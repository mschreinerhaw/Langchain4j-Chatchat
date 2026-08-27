package com.chatchat.mcpserver.templatepublication;

import com.chatchat.mcpserver.api.publication.ApiTemplateDiscoveryMcpToolPublisher;
import com.chatchat.mcpserver.ops.discovery.TemplateDiscoveryMcpToolPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/** Fixed parent template retrieval tools that dynamic query tools may reuse. */
@Component
public class TemplateQueryParentCatalog {

    public static final String SERVICE_ID = "chatchat-mcp-server";
    public static final String SERVICE_NAME = "ChatChat MCP Server";

    private final List<ParentTool> parents = List.of(
        new ParentTool(SERVICE_ID, SERVICE_NAME,
            TemplateDiscoveryMcpToolPublisher.SSH_TEMPLATE_TOOL_NAME,
            "SSH 命令模板检索", TemplateAssetCatalogService.SSH),
        new ParentTool(SERVICE_ID, SERVICE_NAME,
            TemplateDiscoveryMcpToolPublisher.SQL_DATASOURCE_TEMPLATE_TOOL_NAME,
            "数据库运维模板检索", TemplateAssetCatalogService.SQL),
        new ParentTool(SERVICE_ID, SERVICE_NAME,
            TemplateDiscoveryMcpToolPublisher.HTTP_ENDPOINT_TEMPLATE_TOOL_NAME,
            "HTTP 请求模板检索", TemplateAssetCatalogService.HTTP),
        new ParentTool(SERVICE_ID, SERVICE_NAME,
            TemplateDiscoveryMcpToolPublisher.DATABASE_QUERY_TEMPLATE_TOOL_NAME,
            "业务数据库查询模板检索", TemplateAssetCatalogService.DATABASE_QUERY),
        new ParentTool(SERVICE_ID, SERVICE_NAME,
            ApiTemplateDiscoveryMcpToolPublisher.TOOL_NAME,
            "API 服务模板检索", TemplateAssetCatalogService.API)
    );

    public List<ParentTool> list() {
        return parents;
    }

    public ParentTool require(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("parentToolName is required");
        }
        return parents.stream()
            .filter(item -> item.toolName().equalsIgnoreCase(toolName.trim()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported parent template query tool: " + toolName));
    }

    public record ParentTool(String serviceId, String serviceName, String toolName,
                             String title, String assetType) { }
}
