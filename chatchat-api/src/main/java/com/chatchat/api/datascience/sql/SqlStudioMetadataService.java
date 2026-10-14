package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Durable discovery identity and logical bindings; authorization is always reevaluated through MCP. */
@Service @RequiredArgsConstructor
public class SqlStudioMetadataService {
    private final SqlStudioService studio;
    private final SqlStudioMetadataGateway gateway;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public record BindingInput(String serviceId, String assetRef, String role, String database, String schema) { }
    public record Create(String purpose, String targetDialect, List<BindingInput> bindings) { }
    public record Binding(String bindingId, String serviceId, String assetRef, String role, String database, String schema, String sourceDialect) { }
    public record Context(String discoveryId, String projectId, String purpose, String targetDialect, List<Binding> bindings, String state) { }
    public record Operation(String bindingId, Map<String, Object> arguments) { }

    public SqlStudioMetadataGateway.Result assets(HttpServletRequest request, String projectId, String serviceId, int page, int pageSize, String snapshot) {
        studio.project(request, projectId);
        Map<String, Object> args = new LinkedHashMap<>(Map.of("contractVersion", SqlStudioMetadataGateway.CONTRACT,
            "operation", "LIST_ASSETS", "page", page, "pageSize", pageSize));
        if (snapshot != null) args.put("expectedSnapshotId", snapshot);
        return gateway.invoke(request, serviceId, args);
    }

    @Transactional
    public Context create(HttpServletRequest request, String projectId, Create input) {
        var project = studio.project(request, projectId);
        require(input != null && Set.of("QUERY", "ETL").contains(Objects.toString(input.purpose(), "")), "Choose QUERY or ETL purpose");
        require(input.targetDialect() != null && SqlStudioService.DIALECTS.contains(input.targetDialect()), "Unsupported target dialect");
        require(input.bindings() != null && !input.bindings().isEmpty() && input.bindings().size() <= 8, "Choose 1 to 8 logical metadata bindings");
        if ("QUERY".equals(input.purpose())) require(input.bindings().stream().allMatch(binding -> binding != null && "SOURCE".equals(binding.role())), "Query bindings must be SOURCE");
        List<Binding> bindings = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (var binding : input.bindings()) {
            require(binding != null && binding.assetRef() != null && !binding.assetRef().isBlank()
                && Set.of("SOURCE", "TARGET").contains(Objects.toString(binding.role(), "")), "Invalid logical metadata binding");
            require(binding.assetRef().length() <= 128 && validNamespace(binding.database()) && validNamespace(binding.schema()), "Metadata binding is too long");
            var result = asset(request, binding.serviceId(), binding.assetRef());
            require(unique.add(mapperKey(result.serviceId(), binding)), "Duplicate metadata binding");
            bindings.add(new Binding(UUID.randomUUID().toString(), result.serviceId(), binding.assetRef(), binding.role(),
                binding.database(), binding.schema(), sourceDialect(result.metadata(), binding.assetRef())));
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("insert into ds_sql_metadata_context(id,project_id,tenant_id,owner_id,purpose,target_dialect,bindings_json,created_at) values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                id, projectId, project.tenantId(), project.ownerId(), input.purpose(), input.targetDialect(), mapper.writeValueAsString(bindings));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Cannot persist metadata bindings", ex); }
        return new Context(id, projectId, input.purpose(), input.targetDialect(), List.copyOf(bindings), "READY");
    }

    public Context get(HttpServletRequest request, String projectId, String discoveryId) {
        Context context = owned(request, projectId, discoveryId);
        for (var binding : context.bindings()) asset(request, binding.serviceId(), binding.assetRef());
        return context;
    }

    public SqlStudioMetadataGateway.Result operate(HttpServletRequest request, String projectId, String discoveryId, Operation input) {
        Context context = owned(request, projectId, discoveryId);
        require(input != null && input.arguments() != null, "Metadata operation arguments are required");
        Binding binding = context.bindings().stream().filter(value -> value.bindingId().equals(input.bindingId())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata binding not found"));
        Set<String> allowed = Set.of("operation", "query", "tableName", "database", "schema", "page", "pageSize", "expectedSnapshotId");
        require(allowed.containsAll(input.arguments().keySet()), "Unsupported metadata operation argument");
        require(Set.of("LIST_NAMESPACES", "SEARCH", "DESCRIBE").contains(Objects.toString(input.arguments().get("operation"), "")), "Unsupported metadata operation");
        Map<String, Object> args = new LinkedHashMap<>(input.arguments());
        args.put("contractVersion", SqlStudioMetadataGateway.CONTRACT); args.put("assetId", binding.assetRef());
        restrict(args, "database", binding.database()); restrict(args, "schema", binding.schema());
        // The server-stored binding selects the service/asset. The MCP service checks current grants afresh.
        return gateway.invoke(request, binding.serviceId(), args);
    }

    private Context owned(HttpServletRequest request, String projectId, String id) {
        var project = studio.project(request, projectId);
        var rows = jdbc.query("select * from ds_sql_metadata_context where id=? and project_id=? and tenant_id=? and owner_id=?", (row, index) -> {
            try { return new Context(row.getString("id"), projectId, row.getString("purpose"), row.getString("target_dialect"),
                mapper.readValue(row.getString("bindings_json"), new TypeReference<List<Binding>>() { }), "READY"); }
            catch (java.io.IOException ex) { throw new IllegalStateException("Invalid persisted metadata bindings", ex); }
        }, id, projectId, project.tenantId(), project.ownerId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata discovery context not found");
        return rows.get(0);
    }

    private SqlStudioMetadataGateway.Result asset(HttpServletRequest request, String service, String ref) {
        var result = gateway.invoke(request, service, Map.of("contractVersion", SqlStudioMetadataGateway.CONTRACT, "operation", "LIST_ASSETS", "assetId", ref));
        sourceDialect(result.metadata(), ref); // Require the exact authorized asset, never accept a wildcard/empty response.
        return result;
    }
    private String sourceDialect(Map<String, Object> data, String ref) {
        if (data.get("items") instanceof List<?> items && items.size() == 1 && items.get(0) instanceof Map<?, ?> item && ref.equals(item.get("assetRef"))
            && item.get("sourceDialect") instanceof String dialect && !dialect.isBlank()) return dialect;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Metadata asset is unavailable or unauthorized");
    }
    private void restrict(Map<String, Object> args, String key, String fixed) {
        if (fixed == null) return;
        require(!args.containsKey(key) || fixed.equals(args.get(key)), "Operation cannot widen the bound namespace"); args.put(key, fixed);
    }
    private boolean validNamespace(String value) { return value == null || (!value.isBlank() && value.length() <= 256); }
    private String mapperKey(String service, BindingInput binding) { return List.of(service, binding.assetRef(), binding.role(), Objects.toString(binding.database(), ""), Objects.toString(binding.schema(), "")).toString(); }
    private void require(boolean condition, String message) { if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
