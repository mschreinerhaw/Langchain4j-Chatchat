package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.McpCallerSignature;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateAssetCatalogService;
import com.chatchat.mcpserver.templatepublication.catalog.TemplateCallerScope;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateCapabilityPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Template-first publications reuse immutable release persistence and the existing executor registry. */
@Service @RequiredArgsConstructor
public class TemplateCapabilityService {
    public static final String KIND = "TEMPLATE_CAPABILITY";
    public static final String AGENT_TOOL = "AGENT_TOOL";
    private final ToolReleaseRepository releases;
    private final TemplateAssetCatalogService catalog;
    private final TemplateCallerScope callers;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final ToolRegistry tools;
    private final ResourceAuthorizationPort permissions;
    private final InternalCredentialProperties credentials;
    private final ObjectProvider<TemplateCapabilityPublisher> publisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatchat.mcpserver.audit.InvocationAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chatchat.mcpserver.license.McpLicenseService license;

    public List<Map<String, Object>> templates(String tenant, String actor, boolean management) {
        try (var ignored = callers.open(tenant, actor)) {
            return catalog.businessTemplates(management).stream().map(TemplateAssetCatalogService.BoundTemplate::publicView).toList();
        }
    }

    public List<Map<String, Object>> publications(String tenant, String actor) {
        return releases.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
            .filter(row -> KIND.equals(row.getKind()) && actor.equals(row.getActorId()))
            .map(this::view).toList();
    }

    @Transactional
    public synchronized Map<String, Object> publish(String tenant, String actor, String key, String expectedRevision) {
        try (var ignored = callers.open(tenant, actor)) {
            var template = catalog.businessTemplates(false).stream().filter(item -> item.key().equals(key))
                .findFirst().orElseThrow(() -> new SecurityException("Template or asset is not authorized"));
            if (!template.revision().equals(expectedRevision)) throw new IllegalArgumentException("Template changed; refresh and confirm the current version");
            String logicalId = UUID.nameUUIDFromBytes((tenant + "\n" + actor + "\n" + key).getBytes(StandardCharsets.UTF_8)).toString();
            lock(logicalId);
            var history = releases.lockLogical(logicalId);
            var active = history.stream().filter(row -> "RUNNING".equals(row.getState())).findFirst();
            if (active.isPresent() && template.revision().equals(definition(active.get()).path("revision").asText())) {
                refreshAfterCommit(); return view(active.get());
            }
            var row = new ToolRelease();
            row.setId(UUID.randomUUID().toString()); row.setLogicalId(logicalId); row.setTenantId(tenant);
            row.setActorId(actor); row.setKind(KIND); row.setState("RUNNING");
            row.setReleaseNumber(history.stream().mapToInt(ToolRelease::getReleaseNumber).max().orElse(0) + 1);
            row.setTitle(template.title()); row.setDescription(template.description());
            row.setCreatedAt(Instant.now()); row.setPublishedAt(row.getCreatedAt());
            var schema = json.valueToTree(template.inputSchema()); ToolReleaseSchemas.compile(schema);
            row.setDefinitionJson(write(template)); row.setInputSchemaJson(schema.toString());
            row.setOutputSchemaJson("{\"type\":\"object\",\"properties\":{}}");
            row.setTestEvidenceJson(write(Map.of("type", "TEMPLATE_CONTRACT_VALIDATION", "revision", template.revision(),
                "validatedAt", Instant.now().toString(), "executor", template.executor())));
            active.ifPresent(previous -> { previous.setState("OFFLINE"); releases.save(previous); });
            releases.saveAndFlush(row); refreshAfterCommit(); return view(row);
        }
    }

    @Transactional
    public Map<String, Object> offline(String tenant, String actor, String id) {
        var row = releases.findById(id).filter(item -> KIND.equals(item.getKind()) && tenant.equals(item.getTenantId())
            && actor.equals(item.getActorId())).orElseThrow(() -> new SecurityException("Publication is outside current user scope"));
        lock(row.getLogicalId());
        row = releases.lockLogical(row.getLogicalId()).stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
        row.setState("OFFLINE"); releases.saveAndFlush(row); refreshAfterCommit(); return view(row);
    }

    /** Discovery and execution call this same fresh policy. Missing Agent identity never means all Agents. */
    public boolean available(ToolRelease row, Map<String, Object> proof) {
        if (license != null && (license.runtimeDenialReason() != null || license.toolDenialReason(toolName(row)) != null)) return false;
        if (!McpCallerSignature.verify(credentials.resolvedSecret(), proof, System.currentTimeMillis())) return false;
        String tenant = text(proof, "tenantId"), actor = text(proof, "userId"), agent = text(proof, "agentId");
        if (agent.isBlank() || !tenant.equals(row.getTenantId()) || !"RUNNING".equals(row.getState())) return false;
        String name = toolName(row);
        if (!permissions.allowedIdsForAgent(AGENT_TOOL, tenant, actor, Set.of(), Set.of(name), agent).contains(name)) return false;
        try (var ignored = callers.open(tenant, actor)) {
            var snapshot = definition(row);
            return catalog.businessTemplates(false).stream().anyMatch(template -> template.key().equals(snapshot.path("key").asText())
                && template.revision().equals(snapshot.path("revision").asText()));
        }
    }

    public ToolOutput invoke(String name, Map<String, Object> input, Map<String, Object> meta) {
        var row = running().stream().filter(item -> toolName(item).equals(name)).findFirst()
            .orElseThrow(() -> new SecurityException("Template tool is offline"));
        Map<String, Object> proof = map(meta == null ? null : meta.get(McpCallerSignature.KEY));
        if (!available(row, proof)) {
            if (audit != null) audit.recordTemplateCapability(row.getId(), name, Map.of(), false, 0, "Authorization denied");
            throw new SecurityException("Template, asset or Agent authorization is no longer effective");
        }
        long started = System.currentTimeMillis();
        boolean successful = false;
        try (var ignored = callers.open(text(proof, "tenantId"), text(proof, "userId"))) {
            var snapshot = definition(row);
            var schema = json.readTree(row.getInputSchemaJson());
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (var entry : (input == null ? Map.<String,Object>of() : input).entrySet()) {
                if (schema.path("properties").has(entry.getKey()) || !ENVELOPE.contains(entry.getKey())) parameters.put(entry.getKey(), entry.getValue());
            }
            var suppliedParameters = parameters;
            for (String identity : List.of("tenantId", "userId", "agentId")) {
                if (schema.path("properties").has(identity)) suppliedParameters.put(identity, text(proof, identity));
            }
            schema.path("properties").fields().forEachRemaining(property -> {
                if (!suppliedParameters.containsKey(property.getKey()) && property.getValue().has("default")) {
                    suppliedParameters.put(property.getKey(), json.convertValue(property.getValue().get("default"), Object.class));
                }
            });
            ToolReleaseSchemas.validate(schema, json.valueToTree(parameters));
            String executor = snapshot.path("executor").asText();
            boolean parameterContainer = Set.of("linux_command_execute", "sql_query_execute", "python_template_execute", "http_request_execute", "api_template_execute").contains(executor);
            Map<String, Object> arguments = new LinkedHashMap<>(parameterContainer ? Map.of() : parameters);
            if (parameterContainer) arguments.put("parameters", new LinkedHashMap<>(parameters));
            arguments.putAll(json.convertValue(snapshot.path("binding"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>(){}));
            if ("linux_command_execute".equals(executor)) arguments.put(com.chatchat.mcpserver.routing.target.TemplateExecutionTarget.KEY,
                new com.chatchat.mcpserver.routing.target.TemplateExecutionTarget("ssh_host", arguments.get("hostId").toString()));
            if ("sql_query_execute".equals(executor)) arguments.put(com.chatchat.mcpserver.routing.target.TemplateExecutionTarget.KEY,
                new com.chatchat.mcpserver.routing.target.TemplateExecutionTarget("sql_datasource", arguments.get("datasourceId").toString()));
            if ("http_request_execute".equals(executor)) arguments.put(com.chatchat.mcpserver.routing.target.TemplateExecutionTarget.KEY,
                new com.chatchat.mcpserver.routing.target.TemplateExecutionTarget("http_endpoint", arguments.get("endpointId").toString()));
            arguments.put("tenantId", text(proof, "tenantId")); arguments.put("userId", text(proof, "userId"));
            Map<String,Object> trustedContext = new LinkedHashMap<>();
            trustedContext.put("tenantId", text(proof, "tenantId")); trustedContext.put("userId", text(proof, "userId"));
            trustedContext.put("agentId", text(proof, "agentId")); trustedContext.put("authorizationAgentId", text(proof, "agentId"));
            var caller = com.chatchat.mcpserver.mcp.McpInvocationContext.current();
            trustedContext.put("roles", caller == null ? "" : Objects.toString(caller.roles(), ""));
            trustedContext.put("permissionLevel", Objects.toString(arguments.get("permissionLevel"), "read"));
            arguments.put("roles", trustedContext.get("roles"));
            ToolOutput output = tools.executeEnhancedTool(snapshot.path("executor").asText(), ToolInput.builder()
                .parameters(arguments).userId(text(proof, "userId")).context(trustedContext).build());
            if (output == null) throw new IllegalStateException("Template executor produced no result");
            output.setMetadata(new LinkedHashMap<>(output.getMetadata() == null ? Map.of() : output.getMetadata()));
            output.getMetadata().put("templatePublication", Map.of("releaseId", row.getId(), "toolName", name,
                "templateRevision", snapshot.path("revision").asText(), "userId", text(proof, "userId"), "agentId", text(proof, "agentId")));
            successful = output.isSuccess(); return output;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Invalid stored template contract", invalid); }
        finally {
            if (audit != null) audit.recordTemplateCapability(row.getId(), name,
                Map.of("tenantId", text(proof, "tenantId"), "userId", text(proof, "userId"), "agentId", text(proof, "agentId")),
                successful, System.currentTimeMillis() - started, successful ? null : "Template execution failed");
        }
    }

    private static final Set<String> ENVELOPE = Set.of("tenantId", "userId", "username", "roles", "agentId", "mcpContext",
        "traceId", "requestId", "conversationId", "workspaceId", "env", "domain", "assetType", "permissionLevel", "scopeExpression");
    public List<ToolRelease> running() { return releases.findByState("RUNNING").stream().filter(row -> KIND.equals(row.getKind())).toList(); }
    public static String toolName(ToolRelease row) { return "tpl_" + row.getLogicalId().replace("-", ""); }
    public Map<String, Object> view(ToolRelease row) {
        var definition = definition(row);
        return Map.ofEntries(Map.entry("id", row.getId()), Map.entry("logicalId", row.getLogicalId()), Map.entry("toolName", toolName(row)),
            Map.entry("title", row.getTitle()), Map.entry("state", row.getState()), Map.entry("version", row.getReleaseNumber()),
            Map.entry("templateKey", definition.path("templateKey").asText()), Map.entry("revision", definition.path("revision").asText()),
            Map.entry("assetName", definition.path("assetName").asText()),
            Map.entry("category", definition.path("category").asText()),
            Map.entry("description", Objects.toString(row.getDescription(), "")),
            Map.entry("publishedAt", row.getPublishedAt().toString()));
    }
    public com.fasterxml.jackson.databind.JsonNode definition(ToolRelease row) {
        try { return json.readTree(row.getDefinitionJson()); } catch (Exception ex) { throw new IllegalStateException("Invalid stored template publication", ex); }
    }
    private void lock(String identity) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if ("PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    statement.setLong(1, identity.hashCode()); statement.execute();
                }
            }
            return null;
        });
    }
    private void refreshAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.getObject().refreshPublication(); }
        });
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalArgumentException(ex); } }
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object value) { return value instanceof Map<?,?> m ? (Map<String,Object>)m : Map.of(); }
    private String text(Map<String,Object> values, String key) { return Objects.toString(values.get(key), ""); }
}
