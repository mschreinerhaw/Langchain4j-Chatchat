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
    public record CodingSelection(String discoveryId,String bindingId,String expectedSnapshotId,List<CodingTable> tables,List<CodingSelection> sources) {
        public CodingSelection(String discoveryId,String bindingId,String expectedSnapshotId,List<CodingTable> tables){this(discoveryId,bindingId,expectedSnapshotId,tables,null);}
    }
    public record CodingTable(String tableName,String database,String schema) { }
    /** Re-fetch only explicitly selected table structures using the existing MCP grant checks. */
    public Map<String,Object> captureCoding(HttpServletRequest request,String project,CodingSelection selection){
        require(selection!=null,"Choose metadata tables");
        if(selection.sources()!=null){
            require(selection.discoveryId()==null&&selection.bindingId()==null&&selection.expectedSnapshotId()==null&&selection.tables()==null,"Choose either grouped or single metadata selection");
            require(!selection.sources().isEmpty()&&selection.sources().size()<=16,"Choose 1 to 16 metadata groups");
            var merged=new ArrayList<Map<String,Object>>();var identities=new HashSet<List<String>>();var assets=new HashSet<List<String>>();
            for(var source:selection.sources()){
                require(source!=null&&source.sources()==null,"Nested metadata groups are not supported");
                require(source.tables()!=null&&merged.size()+source.tables().size()<=16,"Choose at most 16 metadata tables");
                @SuppressWarnings("unchecked") var captured=(List<Map<String,Object>>)captureCoding(request,project,source).get("tables");
                for(var table:captured){
                    var identity=List.of(Objects.toString(table.get("serviceId"),""),Objects.toString(table.get("assetRef"),""),Objects.toString(table.get("database"),""),Objects.toString(table.get("schema"),""),table.get("tableName").toString());
                    require(identities.add(identity),"Duplicate selected metadata table");assets.add(identity.subList(0,2));merged.add(table);
                }
            }
            require(assets.size()<=8,"Choose at most 8 metadata sources");return Map.of("tables",merged);
        }
        var context=get(request,project,selection.discoveryId());
        var binding=context.bindings().stream().filter(value->value.bindingId().equals(selection.bindingId())).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Metadata binding not found"));
        require(selection.tables()!=null && !selection.tables().isEmpty() && selection.tables().size()<=16,"Choose 1 to 16 metadata tables");
        require(selection.expectedSnapshotId()!=null && !selection.expectedSnapshotId().isBlank(),"Metadata snapshot identity is required");
        List<Map<String,Object>> tables=new ArrayList<>();
        for(var selected:selection.tables()){
            require(selected!=null && selected.tableName()!=null && !selected.tableName().isBlank(),"Invalid selected metadata table");
            var args=new LinkedHashMap<String,Object>(Map.of("operation","DESCRIBE","tableName",selected.tableName(),
                "expectedSnapshotId",selection.expectedSnapshotId(),"page",1,"pageSize",1));
            if(selected.database()!=null)args.put("database",selected.database());
            if(selected.schema()!=null)args.put("schema",selected.schema());
            var result=operate(request,project,selection.discoveryId(),new Operation(selection.bindingId(),args)).metadata();
            require("SUCCEEDED".equals(result.get("status")) && selection.expectedSnapshotId().equals(result.get("snapshotId"))
                && Boolean.TRUE.equals(result.get("doesNotExecuteSql")),"Metadata snapshot changed or is unavailable");
            require(result.get("items") instanceof List<?> items && items.size()==1,"Selected table metadata is unavailable");
            var table=(Map<?,?>)((List<?>)result.get("items")).get(0);
            require(selected.tableName().equals(table.get("tableName")),"Metadata table identity changed");
            if(selected.database()!=null)require(selected.database().equals(table.get("database")),"Metadata database identity changed");
            if(selected.schema()!=null)require(selected.schema().equals(table.get("schema")),"Metadata schema identity changed");
            require(table.get("columns") instanceof List<?> columns && !columns.isEmpty(),"Selected table has no visible columns");
            List<Map<String,String>> columns=new ArrayList<>();
            for(Object raw:(List<?>)table.get("columns")){
                require(raw instanceof Map<?,?> column && column.get("name") instanceof String name && !name.isBlank(),"Invalid metadata column");
                var column=(Map<?,?>)raw;var value=new LinkedHashMap<String,String>();value.put("name",column.get("name").toString());value.put("type",Objects.toString(column.get("columnType"),Objects.toString(column.get("dataType"),"UNKNOWN")));
                for(String key:List.of("comment","columnKey","nullable"))if(column.get(key)!=null)value.put(key,column.get(key).toString());columns.add(value);
            }
            var captured=new LinkedHashMap<String,Object>();captured.put("tableName",selected.tableName());
            if(table.get("database")!=null)captured.put("database",table.get("database"));
            if(table.get("schema")!=null)captured.put("schema",table.get("schema"));
            if(table.get("comment")!=null)captured.put("comment",table.get("comment").toString());
            captured.put("serviceId",binding.serviceId());captured.put("assetRef",binding.assetRef());captured.put("sourceDialect",binding.sourceDialect());
            captured.put("columns",columns);captured.put("relationsStatus",table.getOrDefault("relationsStatus",null));
            captured.put("foreignKeys",table.get("foreignKeys"));captured.put("snapshotId",selection.expectedSnapshotId());tables.add(captured);
        }
        return Map.of("tables",tables);
    }

    public List<SqlStudioMetadataGateway.Provider> providers(HttpServletRequest request, String projectId) {
        studio.project(request, projectId);
        return gateway.providers();
    }

    public SqlStudioMetadataGateway.Result assets(HttpServletRequest request, String projectId, String serviceId, int page, int pageSize, String snapshot) {
        studio.project(request, projectId);
        require(page >= 1 && page <= 1_000_000 && pageSize >= 1 && pageSize <= 100, "Invalid metadata pagination");
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
        var args=boundArguments(context,input);
        var binding=context.bindings().stream().filter(value->value.bindingId().equals(input.bindingId())).findFirst().orElseThrow();
        return gateway.invoke(request, binding.serviceId(), args);
    }

    public Context getScoped(com.chatchat.common.kernel.KernelDataScope scope,String projectId,String discoveryId){
        var rows=jdbc.query("select c.bindings_json,c.purpose,c.target_dialect from ds_sql_metadata_context c join ds_sql_project p on p.id=c.project_id where c.id=? and c.project_id=? and c.tenant_id=? and c.owner_id=? and p.tenant_id=? and p.owner_id=?",
            (row,index)->Map.of("bindings_json",row.getString("bindings_json"),"purpose",row.getString("purpose"),"target_dialect",row.getString("target_dialect")),discoveryId,projectId,scope.tenantId(),scope.userId(),scope.tenantId(),scope.userId());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Metadata discovery context not found");
        try{
            var row=rows.get(0);List<Binding> bindings=mapper.readValue(row.get("bindings_json").toString(),new TypeReference<List<Binding>>(){});
            for(var binding:bindings){var result=gateway.invokeScoped(scope,binding.serviceId(),Map.of("contractVersion",SqlStudioMetadataGateway.CONTRACT,"operation","LIST_ASSETS","assetId",binding.assetRef()));sourceDialect(result.metadata(),binding.assetRef());}
            return new Context(discoveryId,projectId,row.get("purpose").toString(),row.get("target_dialect").toString(),bindings,"READY");
        }catch(java.io.IOException failure){throw new IllegalStateException("Invalid persisted metadata bindings",failure);}
    }

    public SqlStudioMetadataGateway.Result operateScoped(com.chatchat.common.kernel.KernelDataScope scope,Context context,Operation input){
        var args=boundArguments(context,input);
        var binding=context.bindings().stream().filter(value->value.bindingId().equals(input.bindingId())).findFirst().orElseThrow();
        return gateway.invokeScoped(scope,binding.serviceId(),args);
    }

    private Map<String,Object> boundArguments(Context context,Operation input){
        require(input != null && input.arguments() != null, "Metadata operation arguments are required");
        Binding binding = context.bindings().stream().filter(value -> value.bindingId().equals(input.bindingId())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Metadata binding not found"));
        Set<String> allowed = Set.of("operation", "query", "tableName", "database", "schema", "page", "pageSize", "expectedSnapshotId", "includeColumns");
        require(allowed.containsAll(input.arguments().keySet()), "Unsupported metadata operation argument");
        require(Set.of("LIST_NAMESPACES", "SEARCH", "DESCRIBE").contains(Objects.toString(input.arguments().get("operation"), "")), "Unsupported metadata operation");
        Map<String, Object> args = new LinkedHashMap<>(input.arguments());
        args.put("contractVersion", SqlStudioMetadataGateway.CONTRACT); args.put("assetId", binding.assetRef());
        restrict(args, "database", binding.database()); restrict(args, "schema", binding.schema());
        // The server-stored binding selects the service/asset. The MCP service checks current grants afresh.
        return args;
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
