package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.mcpserver.authorization.McpAuthorizationService;
import com.chatchat.mcpserver.templatepublication.binding.TemplateQueryBindingService;
import com.chatchat.mcpserver.templatepublication.publisher.TemplateQueryMcpToolPublisher;
import com.fasterxml.jackson.databind.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

/** Versioned management of the existing controlled executor; it never grants access. */
@Service @RequiredArgsConstructor
public class ToolReleaseService {
    private final ToolReleaseRepository releases;
    private final List<ToolReleaseExecutor> executors;
    private final McpAuthorizationService authorization;
    private final TemplateQueryMcpToolPublisher publisher;
    private final ObjectMapper json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record Draft(String logicalId, String title, String kind, JsonNode definition,
                        JsonNode inputSchema, JsonNode outputSchema, String description) {
        public Draft(String logicalId,String title,String kind,JsonNode definition,JsonNode inputSchema,JsonNode outputSchema) {
            this(logicalId,title,kind,definition,inputSchema,outputSchema,null);
        }
    }
    public record ContractTest(JsonNode input, JsonNode output) { }

    @Transactional(readOnly=true)
    public List<ToolRelease> list(String tenant) { return releases.findByTenantIdOrderByCreatedAtDesc(tenant); }

    @Transactional
    public ToolRelease draft(String tenant, String actor, Draft draft) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length()>256
            || draft.kind() == null || draft.definition()==null || !draft.definition().isObject()) throw new IllegalArgumentException("Invalid tool draft or unsupported executor kind");
        var executor=executor(draft.kind());
        requireTenant(tenant, draft.definition().path("roleId").asText());
        String logical = draft.logicalId() == null || draft.logicalId().isBlank() ? UUID.randomUUID().toString() : draft.logicalId();
        var history = releases.lockLogical(logical);
        if (draft.logicalId() != null && !draft.logicalId().isBlank() && history.isEmpty()) missing();
        history.forEach(row -> requireTenant(row, tenant));
        if (history.isEmpty() && executor.unmanagedExistingDefinition(draft.definition()))
            throw new IllegalArgumentException("Existing tool must be revised from its imported version");
        if (!history.isEmpty()) {
            if (!history.get(0).getKind().equals(draft.kind()) || !executor.stableIdentity(read(history.get(0).getDefinitionJson())).equals(executor.stableIdentity(draft.definition())))
                throw new IllegalArgumentException("Tool identity, target and executor are stable; create another tool to change them");
        }
        ToolRelease row = new ToolRelease(); row.setId(UUID.randomUUID().toString()); row.setLogicalId(logical);
        row.setTenantId(tenant); row.setActorId(actor); row.setCreatedAt(Instant.now());
        row.setReleaseNumber(history.stream().mapToInt(ToolRelease::getReleaseNumber).max().orElse(0)+1);
        row.setState("DRAFT"); row.setKind(draft.kind()); row.setTitle(draft.title());
        row.setDescription(draft.description());
        ToolReleaseSchemas.compile(draft.inputSchema()); ToolReleaseSchemas.compile(draft.outputSchema());
        row.setDefinitionJson(write(draft.definition())); row.setInputSchemaJson(write(draft.inputSchema()));
        row.setOutputSchemaJson(write(draft.outputSchema())); return releases.saveAndFlush(row);
    }

    @Transactional
    public ToolRelease validate(String tenant, String actor, String id) {
        ToolRelease row = locked(tenant,id); requireEditable(row);
        validateDefinition(row); row.setState("VALIDATED"); row.setActorId(actor); return releases.saveAndFlush(row);
    }

    @Transactional
    public ToolRelease test(String tenant, String actor, String id, ContractTest samples) {
        ToolRelease row = locked(tenant,id); requireEditable(row);
        if (!Set.of("VALIDATED","TESTED").contains(row.getState())) throw new IllegalArgumentException("Validate the draft first");
        validateDefinition(row);
        ToolReleaseSchemas.validate(read(row.getInputSchemaJson()), samples == null ? null : samples.input());
        JsonNode actual=executor(row.getKind()).test(read(row.getDefinitionJson()),samples.input());
        ToolReleaseSchemas.validate(read(row.getOutputSchemaJson()),actual);
        if(samples.output()!=null)ToolReleaseSchemas.validate(read(row.getOutputSchemaJson()),samples.output());
        row.setTestEvidenceJson(write(Map.of("type","CONTROLLED_EXECUTOR_CONTRACT_TEST", "testedAt",Instant.now().toString(),
            "actorId",actor,"input",samples.input(),"output",actual)));
        row.setState("TESTED"); row.setActorId(actor); return releases.saveAndFlush(row);
    }

    @Transactional
    public ToolRelease publish(String tenant, String actor, String id, boolean rollback) {
        ToolRelease row = locked(tenant,id);
        if (rollback ? row.getPublishedAt()==null : !"TESTED".equals(row.getState()))
            throw new IllegalArgumentException(rollback ? "Rollback requires a previously published version" : "A successful contract test is required");
        validateDefinition(row); // Recheck revocation at publication and rollback, not only during testing.
        var executor=executor(row.getKind());
        // Serialize schema transitions of the same global tool name across roles and API instances.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            if(connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                try(var statement=connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                    statement.setLong(1,executor.toolName(read(row.getDefinitionJson())).hashCode());statement.execute();
                }
            }
            return null;
        });
        for(var other:releases.findByState("RUNNING")) {
            if(!other.getLogicalId().equals(row.getLogicalId())
                && executor(other.getKind()).toolName(read(other.getDefinitionJson())).equals(executor.toolName(read(row.getDefinitionJson())))
                && (!read(other.getInputSchemaJson()).equals(read(row.getInputSchemaJson()))
                    || !read(other.getOutputSchemaJson()).equals(read(row.getOutputSchemaJson()))))
                throw new IllegalArgumentException("All role bindings of a logical tool must use the same released Schema");
        }
        var history = releases.lockLogical(row.getLogicalId());
        var current = history.stream().filter(item -> "RUNNING".equals(item.getState())).findFirst();
        String bindingId = history.stream().map(ToolRelease::getBindingId).filter(Objects::nonNull).findFirst().orElse(null);
        String executionReference=executor.activate(read(row.getDefinitionJson()),bindingId);
        current.filter(item -> !item.getId().equals(id)).ifPresent(item -> { item.setState("OFFLINE"); releases.save(item); });
        row.setBindingId(executionReference); row.setState("RUNNING");
        if (row.getPublishedAt()==null) {row.setPublishedAt(Instant.now());row.setActorId(actor);}
        // Actor on immutable released definition remains the original publisher. Actions are audited by the API.
        releases.saveAndFlush(row); refreshAfterCommit(); return row;
    }

    @Transactional
    public ToolRelease offline(String tenant, String actor, String id) {
        ToolRelease row=locked(tenant,id);
        if (!"RUNNING".equals(row.getState())) throw new IllegalArgumentException("Only running versions can be taken offline");
        executor(row.getKind()).offline(row.getBindingId()); row.setState("OFFLINE"); releases.saveAndFlush(row); refreshAfterCommit(); return row;
    }

    private void validateDefinition(ToolRelease row) {
        authorization.refreshNow();
        var definition=read(row.getDefinitionJson()); requireTenant(row.getTenantId(),definition.path("roleId").asText());
        executor(row.getKind()).validate(definition); ToolReleaseSchemas.compile(read(row.getInputSchemaJson())); ToolReleaseSchemas.compile(read(row.getOutputSchemaJson()));
    }
    private void refreshAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publisher.refresh(); }
        });
    }
    private ToolRelease locked(String tenant,String id) {
        ToolRelease row=releases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireTenant(row,tenant);
        return releases.lockLogical(row.getLogicalId()).stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
    }
    private void requireEditable(ToolRelease row) {
        if (row.getPublishedAt()!=null) throw new IllegalArgumentException("Released versions are immutable; create a new draft");
    }
    private void requireTenant(ToolRelease row,String tenant) { if (!Objects.equals(row.getTenantId(),tenant)) missing(); }
    private void requireTenant(String tenant,String role) {
        if (authorization.roles(tenant).stream().noneMatch(item -> item.id().equals(role) && "enabled".equalsIgnoreCase(item.status())))
            throw new SecurityException("Role is outside the current tenant or disabled");
    }
    private void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private ToolReleaseExecutor executor(String kind) {
        return executors.stream().filter(item -> item.kind().equals(kind)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported controlled tool kind"));
    }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalArgumentException(ex); } }
    private JsonNode read(String value) { try { return json.readTree(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
}
