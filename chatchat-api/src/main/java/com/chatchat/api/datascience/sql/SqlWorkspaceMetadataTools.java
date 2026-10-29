package com.chatchat.api.datascience.sql;

import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Task-local discovered evidence over existing MCP capabilities. The model chooses every query. */
@Service @RequiredArgsConstructor
public class SqlWorkspaceMetadataTools {
    public static final String PROFILE="runtime-coding:sql-metadata-tools:v1";
    private final ObjectProvider<SqlStudioMetadataService> metadata;
    private final SqlStudioMetadataGateway gateway;
    private final SqlGlotToolchain parser;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectMapper json;
    private static final Set<String> INPUTS=Set.of("operation","bindingId","query","queryTerms","tableName","database","schema","page","pageSize","expectedSnapshotId","includeColumns");

    public static boolean enabled(CodingTaskContract contract){return contract.metadataRefs().contains(PROFILE);}
    public static Set<String> tools(boolean enabled){return enabled?Set.of("workspace_read","workspace_propose","workspace_metadata","workspace_validate"):Set.of("workspace_read","workspace_propose");}
    private String key(CodingTaskContract contract){return "coding:workspace-discovery:"+contract.taskId();}
    private KernelDataScope bounded(CodingTaskContract contract){
        long deadline=Math.min(contract.budget().deadlineAt().toEpochMilli(),System.currentTimeMillis()+15000);
        if(deadline<=System.currentTimeMillis())throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"METADATA_DEADLINE_EXCEEDED");
        var s=contract.scope();return new KernelDataScope(s.tenantId(),s.userId(),s.requestId(),s.conversationId(),s.runId(),s.environment(),Map.of("deadlineAt",deadline));
    }
    public SqlStudioMetadataService.Context context(CodingTaskContract contract,Map<String,Object> schema){
        return context(contract,schema,bounded(contract));
    }
    private SqlStudioMetadataService.Context context(CodingTaskContract contract,Map<String,Object> schema,KernelDataScope scope){
        if(!enabled(contract)||!(schema.get("discoveryId") instanceof String discovery)||!(schema.get("projectId") instanceof String project))
            throw new SecurityException("SQL metadata exploration requires its server-owned database binding");
        return metadata.getObject().getScoped(scope,project,discovery);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> query(CodingTaskContract contract,Map<String,Object> schema,Map<String,Object> input){
        if(!INPUTS.containsAll(input.keySet())||encode(input).length()>16000)throw new IllegalArgumentException("Invalid bounded metadata query");
        var scope=bounded(contract);var context=context(contract,schema,scope);
        String operation=Objects.toString(input.get("operation"),"");
        if(operation.equals("LIST_BINDINGS"))return Map.of("status","SUCCEEDED","bindings",context.bindings(),
            "dialect",context.targetDialect(),"operations",List.of("LIST_NAMESPACES","SEARCH","DESCRIBE","BUSINESS_SEARCH","TEMPLATE_SEARCH","LINEAGE"),
            "limitations",List.of("Metric definitions require returned enterprise evidence; do not invent formulas.","Foreign keys are known relationships; uncollected data lineage is explicitly unknown."),"sqlExecuted",false);
        var binding=context.bindings().stream().filter(b->b.bindingId().equals(input.get("bindingId"))).findFirst()
            .orElseThrow(()->new SecurityException("Choose a binding returned by LIST_BINDINGS"));
        if(Set.of("BUSINESS_SEARCH","TEMPLATE_SEARCH").contains(operation)){
            String query=searchQuery(input);
            if(query.isBlank()||query.length()>512)throw new IllegalArgumentException("Supply a short business search query (max 512)");
            Map<String,Object> args=new LinkedHashMap<>();args.put("query",query);
            if(operation.equals("BUSINESS_SEARCH")){
                if(input.get("queryTerms") instanceof List<?> terms && terms.size()>1)
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"METADATA_QUERY_BUDGET: Search one focused business concept per call; choose further concepts in subsequent calls");
                args.put("queryTerms",List.of(query));
            }else{
                if(binding.schema()!=null)return Map.of("status","UNAVAILABLE_IN_SELECTED_SCOPE","reason","Existing template discovery has no schema boundary; use physical metadata within this schema","sqlExecuted",false);
                args.put("assetId",binding.assetRef());if(binding.database()!=null)args.put("database",binding.database());
                args.put("stage","templates");args.put("catalogLimit",10);args.put("detailLimit",3);
            }
            var data=gateway.referenceScoped(scope,binding.serviceId(),operation.equals("BUSINESS_SEARCH")?"enterprise_metadata_search":"data_query_query",args);
            if(operation.equals("TEMPLATE_SEARCH")){
                // Legacy template discovery may ignore namespace filters. Publish only candidates
                // carrying independently checkable routing identities for this selected binding.
                var scoped=maps(data.getOrDefault("candidates",data.get("templates"))).stream().filter(row->
                    binding.assetRef().equals(Objects.toString(row.getOrDefault("assetRef",row.getOrDefault("assetId",row.get("datasourceId"))),""))
                    &&(binding.database()==null||binding.database().equals(row.get("database")))).toList();
                data=Map.of("candidates",scoped,"scopeVerified",true,"unverifiedCandidatesOmitted",true,
                    "limitation","Templates without explicit datasource/database provenance are not delivered. Retrieval does not authorize execution.");
            }
            return boundedResult(Map.of("status","SUCCEEDED","operation",operation,"bindingId",binding.bindingId(),"data",data,"sqlExecuted",false,
                "evidenceScope",operation.equals("BUSINESS_SEARCH")?"ENTERPRISE_STANDARD_REFERENCES_NOT_PHYSICAL_TABLE_AUTHORITY":"AUTHORIZED_TEMPLATE_REFERENCES"));
        }
        if(!Set.of("LIST_NAMESPACES","SEARCH","DESCRIBE","LINEAGE").contains(operation))throw new IllegalArgumentException("Unsupported metadata operation");
        var args=new LinkedHashMap<>(input);args.remove("bindingId");args.remove("queryTerms");
        if(operation.equals("SEARCH")){
            String query=searchQuery(input);if(!query.isBlank())args.put("query",query);
        }
        args.put("operation",operation.equals("LINEAGE")?"DESCRIBE":operation);args.putIfAbsent("page",1);args.putIfAbsent("pageSize",10);
        if(operation.equals("SEARCH"))args.putIfAbsent("includeColumns",false);
        String hash=evidence.contentHash(Map.of("bindingId",binding.bindingId(),"arguments",args));
        var state=state(contract);var entries=map(state.get("queries"));
        if(entries.get(hash) instanceof Map<?,?> prior){
            var cached=map(prior);
            try{checkSnapshot(scope,context,binding.bindingId(),cached.get("snapshotId"));}
            catch(ResponseStatusException changed){if(changed.getStatusCode().value()==409)invalidate(contract,binding.bindingId());throw changed;}
            var reused=new LinkedHashMap<>(cached);reused.put("reusedEvidence",true);return reused;
        }
        var raw=metadata.getObject().operateScoped(scope,context,new SqlStudioMetadataService.Operation(binding.bindingId(),args)).metadata();
        if(!"SUCCEEDED".equals(raw.get("status")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"METADATA_SERVICE_UNAVAILABLE: "+raw.get("status"));
        var result=new LinkedHashMap<>(raw);result.put("bindingId",binding.bindingId());result.put("operation",operation);result.put("sqlExecuted",false);
        if(operation.equals("LINEAGE")){result.put("lineageStatus","NOT_COLLECTED");result.put("relationshipSource","JDBC_IMPORTED_KEYS");}
        List<Map<String,Object>> items=maps(raw.get("items"));
        if(Set.of("DESCRIBE","LINEAGE").contains(operation)&&items.isEmpty()){result.put("status","NOT_FOUND");result.put("category","OBJECT_NOT_FOUND_OR_NOT_VISIBLE");}
        boundedResult(result);
        evidence.mutateExecutionCheckpoint(contract.scope(),key(contract),previous->{
            var saved=decode(previous);var snapshots=map(saved.get("snapshots"));var tables=maps(saved.get("tables"));var queries=map(saved.get("queries"));
            Object old=snapshots.put(binding.bindingId(),raw.get("snapshotId"));
            if(old!=null&&!old.equals(raw.get("snapshotId"))){tables.removeIf(t->binding.bindingId().equals(t.get("bindingId")));queries.entrySet().removeIf(e->binding.bindingId().equals(map(e.getValue()).get("bindingId")));}
            for(var item:items)if(item.get("tableName") instanceof String&&item.get("columns") instanceof List<?> columns&&!columns.isEmpty()){
                var table=new LinkedHashMap<>(item);table.put("bindingId",binding.bindingId());
                tables.removeIf(t->Objects.equals(t.get("tableRef"),table.get("tableRef"))&&binding.bindingId().equals(t.get("bindingId")));tables.add(table);
            }
            if(tables.size()>64)throw new IllegalArgumentException("Discovered table cache limit reached; narrow the request");
            queries.put(hash,result);while(queries.size()>32)queries.remove(queries.keySet().iterator().next());
            saved.put("tables",tables);saved.put("snapshots",snapshots);saved.put("queries",queries);
            if(encode(saved).length()>750000)throw new IllegalArgumentException("Metadata context budget reached");return encode(saved);
        });
        return result;
    }
    private String searchQuery(Map<String,Object> input){
        var parts=new ArrayList<String>();
        if(input.get("query")!=null){
            if(!(input.get("query") instanceof String query))throw new IllegalArgumentException("Expected a text search query");
            if(!query.isBlank())parts.add(query.trim());
        }
        if(input.containsKey("queryTerms")){
            if(!(input.get("queryTerms") instanceof List<?> terms)||terms.isEmpty()||terms.size()>12)
                throw new IllegalArgumentException("Expected 1 to 12 short search concepts");
            for(Object term:terms){
                if(!(term instanceof String text)||text.isBlank()||text.length()>128)throw new IllegalArgumentException("Expected short text search concepts");
                parts.add(text.trim());
            }
        }
        String query=String.join(" ",new LinkedHashSet<>(parts));
        if(query.length()>512)throw new IllegalArgumentException("Search query is too long (max 512)");
        return query;
    }
    public Map<String,Object> validate(CodingTaskContract contract,Map<String,Object> schema,List<SqlOutputBundle.Candidate> files,String dialect){
        var syntax=parser.validate(dialect,files,Map.of());if(!Boolean.TRUE.equals(syntax.get("valid")))return syntax;
        var scope=bounded(contract);var context=context(contract,schema,scope);var state=state(contract);
        for(var snapshot:map(state.get("snapshots")).entrySet())try{checkSnapshot(scope,context,snapshot.getKey(),snapshot.getValue());}
        catch(ResponseStatusException changed){if(changed.getStatusCode().value()==409)invalidate(contract,snapshot.getKey());throw changed;}
        var tables=maps(state.get("tables"));
        // Seed tables are context only; Cline confirms them through the same metadata tools before validation.
        var missing=maps(syntax.get("references")).stream().filter(ref->tables.stream().noneMatch(t->SqlStudioReferenceResolver.matches(t,ref))).toList();
        if(!missing.isEmpty())return Map.of("valid",false,"status","METADATA_REQUIRED","sqlValidity","UNDETERMINED","sqlExecuted",false,
            "fieldValidation","PENDING_METADATA","missingObjects",missing,"diagnostics",List.of(Map.of("category","METADATA_REQUIRED",
                "message","Use workspace_metadata to discover/describe the referenced objects, then validate again. Snapshot absence does not prove invalid SQL.")));
        return parser.validate(dialect,files,Map.of("tables",tables));
    }
    private void checkSnapshot(KernelDataScope scope,SqlStudioMetadataService.Context context,String binding,Object snapshot){
        if(snapshot==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"METADATA_SNAPSHOT_UNAVAILABLE");
        var fresh=metadata.getObject().operateScoped(scope,context,new SqlStudioMetadataService.Operation(binding,
            Map.of("operation","SEARCH","page",1,"pageSize",1,"includeColumns",false,"expectedSnapshotId",snapshot))).metadata();
        if(!"SUCCEEDED".equals(fresh.get("status")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"METADATA_SERVICE_UNAVAILABLE");
        if(!snapshot.equals(fresh.get("snapshotId")))throw new ResponseStatusException(HttpStatus.CONFLICT,"METADATA_STALE: query metadata again without the previous snapshot");
    }
    public static Map<String,Object> failure(Exception error){
        int status=error instanceof ResponseStatusException response?response.getStatusCode().value():0;
        String category=error instanceof SecurityException||status==403?"AUTHORIZATION":status==409?"METADATA_STALE":status==429?"QUERY_BUDGET":status>=500||error instanceof IllegalStateException?"METADATA_SERVICE_UNAVAILABLE":"VALIDATION";
        return Map.of("status","FAILED","valid",false,"sqlValidity","UNDETERMINED","sqlExecuted",false,"retryable",status==429||status>=500||error instanceof IllegalStateException,
            "category",category,"diagnostics",List.of(Map.of("category",category,"message",Objects.toString(error.getMessage(),"Metadata request failed"))));
    }
    private void invalidate(CodingTaskContract contract,String binding){
        evidence.mutateExecutionCheckpoint(contract.scope(),key(contract),previous->{var saved=decode(previous);
            var tables=maps(saved.get("tables"));tables.removeIf(t->binding.equals(t.get("bindingId")));
            var snapshots=map(saved.get("snapshots"));snapshots.remove(binding);
            var queries=map(saved.get("queries"));queries.entrySet().removeIf(e->binding.equals(map(e.getValue()).get("bindingId")));
            saved.put("tables",tables);saved.put("snapshots",snapshots);saved.put("queries",queries);return encode(saved);
        });
    }
    private Map<String,Object> boundedResult(Map<String,Object> value){if(encode(value).length()>65536)throw new IllegalArgumentException("Metadata response too large; request fewer tables or columns");return value;}
    private Map<String,Object> state(CodingTaskContract contract){return decode(evidence.readExecutionCheckpoint(contract.scope(),key(contract)).orElse(null));}
    private Map<String,Object> decode(String value){try{return value==null?new LinkedHashMap<>():json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception error){throw new IllegalStateException("Invalid task metadata evidence",error);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid metadata data",e);}}
    private Map<String,Object> map(Object value){return value instanceof Map<?,?>?json.convertValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}):new LinkedHashMap<>();}
    private List<Map<String,Object>> maps(Object value){return value instanceof List<?>?json.convertValue(value,new TypeReference<ArrayList<Map<String,Object>>>(){}):new ArrayList<>();}
}
