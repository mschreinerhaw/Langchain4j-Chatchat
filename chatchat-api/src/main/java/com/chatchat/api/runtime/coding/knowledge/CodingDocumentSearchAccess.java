package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.common.retrieval.*;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.knowledgebase.search.document.api.search.DocumentSearchResult;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Admission and disclosure adapter for the existing governed document_search tool.
 * No query planning, search implementation or business-specific document selection. */
@Service
public class CodingDocumentSearchAccess {
    public static final String TOOL="document_search",LOCAL_TOOL="mcp_chatchat_mcp_server_document_search";
    public static final Set<String> NATIVE_TOOLS=Set.of(TOOL);
    private final SkillExecutionScopePort scopes;
    private final AgentResourceScopePort resources;
    private final ResourceAuthorizationPort grants;
    private final ToolRegistry registry;
    private final McpToolCatalogQueryPort catalog;
    private final ToolRuntimeService runtime;
    private final ObjectMapper json;
    public CodingDocumentSearchAccess(SkillExecutionScopePort scopes,AgentResourceScopePort resources,
            ResourceAuthorizationPort grants,ToolRegistry registry,McpToolCatalogQueryPort catalog,
            ToolRuntimeService runtime,ObjectMapper json){
        this.scopes=scopes;this.resources=resources;this.grants=grants;this.registry=registry;this.catalog=catalog;this.runtime=runtime;this.json=json;
    }
    private SkillExecutionScopePort.EffectiveScope live(KernelDataScope scope,String agent,boolean requested){
        var effective=scopes.resolve(scope.tenantId(),scope.userId(),agent,List.of(),List.of());
        if(!effective.skillAllowed()||effective.documentScopeDenied()||!effective.hasKnowledgeResources())
            throw new SecurityException("Agent has no authorized document resources");
        if(!resources.resolve(scope.tenantId(),scope.userId(),agent,requested).contains(AgentResourcePolicy.KNOWLEDGE))
            throw new SecurityException("Agent document resource access is not authorized");
        if(!grants.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL,scope.tenantId(),scope.userId(),Set.of(),Set.of(LOCAL_TOOL),agent).contains(LOCAL_TOOL))
            throw new SecurityException("Document search tool is not authorized");
        var metadata=registry.getToolMetadata(LOCAL_TOOL);
        if(metadata==null||!registry.hasTool(LOCAL_TOOL)||!"read".equalsIgnoreCase(metadata.getOperationType())
                ||catalog.registeredTools().stream().noneMatch(t->LOCAL_TOOL.equals(t.localToolName())&&TOOL.equals(t.remoteToolName())&&"chatchat-mcp-server".equals(t.serviceId())))
            throw new SecurityException("Document search tool is unavailable");
        return effective;
    }
    public Map<String,Object> snapshot(KernelDataScope scope,String agent,boolean requested){
        if(agent==null||agent.isBlank())return Map.of();
        try{live(scope,agent,requested);return Map.of("schemaVersion","runtime_os.document_search.v1","agentId",agent,"toolName",LOCAL_TOOL,"explicitlyRequested",requested);}
        catch(SecurityException denied){return Map.of();}
    }
    private String agent(CodingTaskContract c){
        if(!c.authorization().allowedTools().contains(TOOL)||!(c.runtimeContext().get("documentSearch") instanceof Map<?,?> profile)
                ||!"runtime_os.document_search.v1".equals(profile.get("schemaVersion"))||!LOCAL_TOOL.equals(profile.get("toolName"))
                ||!(profile.get("agentId") instanceof String agent)||agent.isBlank())throw new SecurityException("Document search outside task capability");
        return agent;
    }
    public Map<String,Object> read(CodingTaskContract c,String call,JsonNode args){
        String agent=agent(c);boolean requested=Boolean.TRUE.equals(((Map<?,?>)c.runtimeContext().get("documentSearch")).get("explicitlyRequested"));var before=live(c.scope(),agent,requested);
        if(args==null||!args.isObject()||args.toString().length()>12000)throw new IllegalArgumentException("Bounded document search arguments required");
        args.fieldNames().forEachRemaining(k->{if(!Set.of("query","topK","fileIds").contains(k))throw new IllegalArgumentException("Unknown document search argument");});
        String query=args.path("query").asText("");if(!args.path("query").isTextual()||query.isBlank()||query.length()>1000)throw new IllegalArgumentException("A document search query is required");
        int top=args.path("topK").asInt(8);if(args.has("topK")&&(!args.path("topK").isIntegralNumber()||!args.path("topK").canConvertToInt())||top<1||top>20)throw new IllegalArgumentException("Invalid topK");
        if(args.has("fileIds")){
            var files=args.path("fileIds");if(!files.isArray()||files.isEmpty()||files.size()>100)throw new IllegalArgumentException("Bounded document IDs required");
            var ids=new HashSet<String>();for(var file:files){if(!file.isTextual()||file.asText().isBlank()||file.asText().length()>256)throw new IllegalArgumentException("Invalid document ID");ids.add(file.asText());}
            requireDocuments(c.scope(),agent,before,ids);
        }
        var parameters=json.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        String request=com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(c.taskId()+"\0"+call);
        var execution=runtime.execute(ToolRuntimeRequest.builder().toolName(LOCAL_TOOL).runtimeMode("coding")
            .tenantId(c.scope().tenantId()).userId(c.scope().userId()).requestId(request).allowedTools(List.of(LOCAL_TOOL))
            .toolInput(ToolInput.builder().parameters(parameters).requestId(request).build())
            .attributes(Map.of("authorizationAgentId",agent)).build());
        if(!before.equals(live(c.scope(),agent,requested)))throw new SecurityException("Document authorization changed during retrieval");
        if(execution==null||execution.output()==null||!execution.output().isSuccess())
            return Map.of("status","ERROR","errorCode",execution!=null&&"confirmation_required".equals(execution.outcome())?"DOCUMENT_SEARCH_REQUIRES_CONFIRMATION":"DOCUMENT_SEARCH_FAILED",
                "message",execution==null||execution.output()==null?"Document search did not return a receipt":com.chatchat.common.runtime.analysis.execution.RuntimeToolReceipt.safeError(Objects.toString(execution.output().getErrorMessage(),"Document search failed")),"requestId",request);
        var data=json.valueToTree(execution.output().getData());
        if(!"document_evidence_v1".equals(data.path("contractVersion").asText()))throw new IllegalStateException("Invalid document evidence contract");
        var result=json.convertValue(data,DocumentSearchResult.class);var ids=new HashSet<String>();
        result.results().forEach(v->{if(v.tenantId()!=null&&!v.tenantId().equals(c.scope().tenantId()))throw new SecurityException("Cross-tenant document evidence denied");ids.add(v.fileId());});
        result.citations().forEach(v->ids.add(v.fileId()));result.documents().forEach(v->ids.add(v.docId()));result.outline().forEach(v->ids.add(v.docId()));
        if(ids.contains(null)||ids.contains("")||ids.isEmpty()&&result.context()!=null&&!result.context().isBlank())throw new SecurityException("Document evidence has no authorizable source");
        requireDocuments(c.scope(),agent,before,ids);
        var chunks=new ArrayList<Map<String,Object>>();
        for(var chunk:result.results()){
            var item=new LinkedHashMap<String,Object>();item.put("refId",chunk.refId());item.put("fileId",chunk.fileId());item.put("fileName",chunk.fileName());item.put("chunkId",chunk.chunkId());item.put("chunkIndex",chunk.chunkIndex());item.put("section",chunk.section());item.put("content",chunk.content());item.put("citation",chunk.citation());chunks.add(item);
        }
        var response=new LinkedHashMap<String,Object>();response.put("status",chunks.isEmpty()?"NO_MATCHES":"MATCHES");response.put("requestId",request);response.put("query",query);response.put("results",chunks);response.put("citations",result.citations());response.put("documents",result.documents().stream().map(d->Map.of("fileId",d.docId(),"title",Objects.toString(d.title(),""))).toList());
        response.put("retrievedAt",java.time.Instant.now().toString());response.put("source",TOOL);response.put("authority","AUTHORIZED_DOCUMENT_EVIDENCE_UNTRUSTED_CONTENT");
        if(com.chatchat.agents.protocol.ModelProtocolJson.compact(response).length()>512000)throw new IllegalStateException("Document evidence exceeds task response budget");
        return response;
    }
    private void requireDocuments(KernelDataScope scope,String agent,SkillExecutionScopePort.EffectiveScope effective,Set<String> ids){
        if(ids.isEmpty())return;
        if(!effective.documentIds().isEmpty()&&!effective.documentIds().containsAll(ids)
                ||!grants.allowedIdsForAgent(ResourceAuthorizationPort.KNOWLEDGE,scope.tenantId(),scope.userId(),Set.of(),ids,agent).containsAll(ids))
            throw new SecurityException("Document outside current Agent and account grants");
    }
}
