package com.chatchat.mcpserver.external;

import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateQueryParentCatalog;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ExternalMcpRegistryService {
    private static final Set<String> SUPPORTED_PARENTS = Set.of(
        TemplateAssetCatalogService.API, TemplateAssetCatalogService.HTTP,
        TemplateAssetCatalogService.SQL, TemplateAssetCatalogService.DATABASE_QUERY);

    private final ExternalMcpServiceRepository repository;
    private final TemplateQueryParentCatalog parentCatalog;
    private final List<ExternalMcpExecutionWorkflow> workflows;
    private final ObjectMapper objectMapper;
    private final InternalCredentialProperties credentials;

    @Transactional(readOnly = true)
    public List<ExternalMcpService> list() { return repository.findAllByOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public ExternalMcpService require(String id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("External MCP service not found: " + id));
    }

    public List<String> workflowIds() { return workflows.stream().map(ExternalMcpExecutionWorkflow::id).sorted().toList(); }

    public List<TemplateQueryParentCatalog.ParentTool> parents() {
        return parentCatalog.list().stream().filter(parent -> SUPPORTED_PARENTS.contains(parent.assetType())).toList();
    }

    public String parentAssetType(ExternalMcpService service) {
        return parentCatalog.require(service.getParentToolName()).assetType();
    }

    @Transactional
    public ExternalMcpService create(UpsertRequest request) {
        ExternalMcpService service = new ExternalMcpService();
        apply(service, request);
        service.setEnabled(false); // Explicit approval after discovery is required.
        return repository.save(service);
    }

    @Transactional
    public ExternalMcpService update(String id, UpsertRequest request) {
        ExternalMcpService service = require(id);
        String oldEndpoint = service.getEndpoint();
        String oldWorkflow = service.getWorkflowId();
        String oldParent = service.getParentToolName();
        String oldAuth = service.getAuthorization();
        apply(service, request);
        if (!Objects.equals(oldEndpoint, service.getEndpoint())
            || !Objects.equals(oldWorkflow, service.getWorkflowId())
            || !Objects.equals(oldParent, service.getParentToolName())
            || !Objects.equals(oldAuth, service.getAuthorization())) {
            service.setEnabled(false);
            service.setTemplatesJson(null);
            service.setDiscoveredAt(null);
        }
        return repository.save(service);
    }

    @Transactional
    public ExternalMcpService discover(String id) {
        ExternalMcpService service = require(id);
        List<McpSchema.Tool> remoteTools = workflow(service).discover(service);
        Set<String> names = new HashSet<>();
        List<ToolTemplate> templates = new ArrayList<>();
        for (McpSchema.Tool tool : remoteTools) {
            if (tool.name() == null || tool.name().isBlank() || !names.add(tool.name())) {
                throw new IllegalArgumentException("External MCP returned a missing or duplicate tool name");
            }
            boolean readOnly = tool.annotations() != null
                && Boolean.TRUE.equals(tool.annotations().readOnlyHint())
                && !Boolean.TRUE.equals(tool.annotations().destructiveHint());
            templates.add(new ToolTemplate(tool.name(),
                tool.title() == null || tool.title().isBlank() ? tool.name() : tool.title(),
                tool.description() == null ? "" : tool.description(),
                tool.inputSchema() == null ? Map.of() : tool.inputSchema(), readOnly));
        }
        try { service.setTemplatesJson(objectMapper.writeValueAsString(templates)); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize discovered MCP tools", failure); }
        service.setDiscoveredAt(Instant.now());
        service.setEnabled(false); // Any contract change needs a fresh explicit approval.
        return repository.save(service);
    }

    @Transactional(readOnly = true)
    public List<ToolTemplate> templates(ExternalMcpService service) {
        if (service.getTemplatesJson() == null || service.getTemplatesJson().isBlank()) return List.of();
        try { return objectMapper.readValue(service.getTemplatesJson(), new TypeReference<>() {}); }
        catch (Exception failure) { throw new IllegalStateException("Invalid stored MCP template snapshot", failure); }
    }

    @Transactional
    public ExternalMcpService setEnabled(String id, boolean enabled) {
        ExternalMcpService service = require(id);
        if (enabled && service.getDiscoveredAt() == null) {
            throw new IllegalArgumentException("Discover MCP tools before enabling this service");
        }
        service.setEnabled(enabled);
        return repository.save(service);
    }

    @Transactional(readOnly = true)
    public McpSchema.CallToolResult invoke(String id, String toolName, Map<String, Object> arguments) {
        ExternalMcpService service = require(id);
        if (!service.isEnabled()) throw new IllegalArgumentException("External MCP service is disabled");
        ToolTemplate template = templates(service).stream().filter(item -> item.name().equals(toolName))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("MCP tool is not a registered template: " + toolName));
        if (!template.readOnly()) throw new IllegalArgumentException("Only read-only external MCP templates are callable");
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        Object properties = template.inputSchema().get("properties");
        if (properties instanceof Map<?, ?> allowed) {
            supplied = supplied.entrySet().stream().filter(entry -> allowed.containsKey(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return workflow(service).invoke(service, template.name(), supplied);
    }

    @Transactional
    public void delete(String id) { repository.delete(require(id)); }

    private void apply(ExternalMcpService service, UpsertRequest request) {
        if (request == null || request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("name is required");
        if (request.endpoint() == null || request.endpoint().isBlank())
            throw new IllegalArgumentException("endpoint is required");
        URI uri;
        try { uri = URI.create(request.endpoint().trim()); }
        catch (Exception failure) { throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URL"); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP(S) URL without credentials or fragment");
        }
        TemplateQueryParentCatalog.ParentTool parent = parentCatalog.require(request.parentToolName());
        if (!SUPPORTED_PARENTS.contains(parent.assetType()))
            throw new IllegalArgumentException("The selected parent is not an API, database or HTTP template");
        String workflowId = request.workflowId() == null || request.workflowId().isBlank()
            ? "mcp_streamable_http" : request.workflowId().trim();
        if (workflows.stream().noneMatch(item -> item.id().equals(workflowId)))
            throw new IllegalArgumentException("Unsupported external MCP workflow: " + workflowId);
        service.setName(request.name().trim());
        service.setEndpoint(uri.toString());
        service.setParentToolName(parent.toolName());
        service.setWorkflowId(workflowId);
        if (request.authorization() != null && !request.authorization().isBlank())
            service.setAuthorization(InternalSecretCipher.encrypt(
                request.authorization().trim(), credentials.resolvedSecret()));
    }

    private ExternalMcpExecutionWorkflow workflow(ExternalMcpService service) {
        return workflows.stream().filter(item -> item.id().equals(service.getWorkflowId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported external MCP workflow: " + service.getWorkflowId()));
    }

    public record UpsertRequest(String name, String endpoint, String authorization,
                                String parentToolName, String workflowId) { }
    public record ToolTemplate(String name, String title, String description,
                               Map<String, Object> inputSchema, boolean readOnly) { }
}
