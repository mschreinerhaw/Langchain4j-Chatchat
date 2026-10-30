package com.chatchat.api.datascience.sql;

import com.chatchat.api.service.ApiKernelScopeResolver;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Uses the existing MCP directory, authenticated envelope and Runtime kernel. No direct database connection. */
@Component @RequiredArgsConstructor @lombok.extern.slf4j.Slf4j
public class SqlStudioMetadataGateway {
    public static final String CONTRACT = "sql-studio.metadata.v2";
    private static final String TOOL = "sql_schema_context_query";
    private final McpRuntimeKernel kernel;
    private final ApiKernelScopeResolver scopes;
    private com.chatchat.common.retrieval.ResourceAuthorizationPort catalogAuthorization;
    private com.chatchat.enterprise.repository.mcp.McpToolAssetRepository catalogRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCatalogAuthorization(com.chatchat.common.retrieval.ResourceAuthorizationPort value){catalogAuthorization=value;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCatalogRegistry(com.chatchat.enterprise.repository.mcp.McpToolAssetRepository value){catalogRegistry=value;}
    private void authorizeCatalog(com.chatchat.common.kernel.KernelDataScope scope,McpToolDescriptor tool){
        if(catalogAuthorization==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"METADATA_AUTHORIZATION_UNAVAILABLE");
        Set<String> names=new LinkedHashSet<>();names.add(tool.localToolName());
        if(catalogRegistry!=null)catalogRegistry.findByServiceIdOrderByLocalToolNameAsc(tool.serviceId()).stream()
            .filter(t->tool.remoteToolName().equals(t.getRemoteToolName()))
            .forEach(t->names.add(t.getLocalToolName()));
        if(!catalogAuthorization.readableMetadataIds(scope.tenantId(),scope.userId(),names).containsAll(names))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"METADATA_ACCESS_DENIED");
    }

    public record Result(String serviceId, Map<String, Object> metadata) { }
    public record Provider(String serviceId, String name) { }

    public List<Provider> providers() {
        Set<String> supported = kernel.tools(new McpToolQuery(null, null, Set.of(TOOL))).stream()
            .filter(tool -> TOOL.equals(tool.remoteToolName()) && supportsV2(tool)).map(McpToolDescriptor::serviceId)
            .collect(java.util.stream.Collectors.toSet());
        return kernel.services().stream().filter(service -> service.enabled() && supported.contains(service.serviceId()))
            .map(service -> new Provider(service.serviceId(), service.name())).sorted(Comparator.comparing(Provider::serviceId)).toList();
    }
    private boolean supportsV2(McpToolDescriptor tool) {
        Object properties = tool.inputSchema().get("properties");
        Object version = properties instanceof Map<?, ?> props ? props.get("contractVersion") : null;
        Object versions = version instanceof Map<?, ?> field ? field.get("enum") : null;
        return versions instanceof Collection<?> values && values.contains(CONTRACT);
    }

    public Result invoke(HttpServletRequest request, String serviceId, Map<String, Object> arguments) {
        var authenticated = scopes.resolve(Map.of(), UUID.randomUUID().toString(), request);
        return invokeScoped(authenticated, serviceId, arguments);
    }

    /** Only application-owned, authenticated task scopes may call this overload. */
    public Result invokeScoped(com.chatchat.common.kernel.KernelDataScope authenticated, String serviceId, Map<String, Object> arguments) {
        if (authenticated.userId() == null || authenticated.tenantId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated metadata caller is required");
        List<McpToolDescriptor> tools = kernel.tools(new McpToolQuery(serviceId, null, Set.of(TOOL))).stream()
            .filter(tool -> TOOL.equals(tool.remoteToolName())).toList();
        if (tools.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Select one registered MCP service providing SQL metadata discovery");
        var tool = tools.get(0);
        authorizeCatalog(authenticated,tool);
        // Version support must be declared by the registered contract, not guessed from an endpoint name.
        Object properties = tool.inputSchema().get("properties");
        Object version = properties instanceof Map<?, ?> props ? props.get("contractVersion") : null;
        Object versions = version instanceof Map<?, ?> field ? field.get("enum") : null;
        if (!(versions instanceof Collection<?> supported) || !supported.contains(CONTRACT))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP SQL metadata v2 is not available on the selected service");
        var context = new LinkedHashMap<String,Object>();
        context.put("tenantId",authenticated.tenantId());context.put("userId",authenticated.userId());
        // This metadata-only protocol has no conversation argument. The legacy registry adapter
        // would otherwise inject conversationId into business arguments; correlate by request/run instead.
        if(authenticated.runId()!=null)context.put("runId",authenticated.runId());
        context.put("scopeAuthority","API_AUTHENTICATION_CONTEXT");context.put("invocationSource","sql_studio_metadata");
        long deadline = System.currentTimeMillis() + 30_000;
        if(authenticated.attributes().get("deadlineAt") instanceof Number taskDeadline)deadline=Math.min(deadline,taskDeadline.longValue());
        var call = new McpServiceCall(null, authenticated.requestId(), tool.serviceId(), tool.remoteToolName(), arguments,
            context, deadline);
        var result = kernel.execute(call);
        if (result == null || !result.successful()) {
            if(authenticated.attributes().get("deadlineAt") instanceof Number taskDeadline&&taskDeadline.longValue()<=System.currentTimeMillis())
                throw new com.chatchat.agents.orchestration.model.AgentDeadlineExceededException("SQL metadata task deadline reached during tool invocation");
            if(result!=null&&Set.of("MCP_TOOL_TIMEOUT","MCP_CALL_DEADLINE_EXCEEDED").contains(Objects.toString(result.errorCode(),"")))
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"METADATA_TOOL_TIMEOUT: 元数据工具请求超时");
            if(result!=null&&Objects.toString(result.errorMessage(),"").contains("METADATA_SNAPSHOT_CHANGED"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"SNAPSHOT_CONFLICT: 元数据或授权范围发生变化，请重新翻页");
            if(result!=null&&Objects.toString(result.errorMessage(),"").contains("Ambiguous table"))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"METADATA_AMBIGUOUS_OBJECT: 存在同名表，请指定数据库和 Schema");
            if(result!=null&&Objects.toString(result.errorMessage(),"").contains("IllegalArgumentException:"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"METADATA_ARGUMENTS_INVALID: 检查工具协议、名称、命名空间及分页参数");
            boolean denied=result!=null&&(result.status()==McpServiceResultStatus.REJECTED
                ||Objects.toString(result.errorMessage(),"").contains("SecurityException:"));
            HttpStatus status = denied ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "SQL metadata operation failed or was denied");
        }
        Map<String, Object> data = payload(result.data(), 0);
        if (!CONTRACT.equals(data.get("contractVersion")) || !Boolean.TRUE.equals(data.get("doesNotExecuteSql")))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid metadata-only MCP response");
        return new Result(tool.serviceId(), data);
    }

    /** Existing read-only enterprise reference and template tools; never a generic MCP proxy. */
    public Map<String,Object> referenceScoped(com.chatchat.common.kernel.KernelDataScope scope,String serviceId,String name,Map<String,Object> arguments) {
        if(scope.userId()==null || scope.tenantId()==null || !Set.of("enterprise_metadata_search","data_query_query").contains(name))
            throw new SecurityException("Authenticated read-only metadata tool required");
        var tools=kernel.tools(new McpToolQuery(serviceId,null,Set.of(name))).stream().filter(t->name.equals(t.remoteToolName())).toList();
        if(tools.size()!=1)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"METADATA_CAPABILITY_UNAVAILABLE: "+name);
        authorizeCatalog(scope,tools.get(0));
        long deadline=Math.min(System.currentTimeMillis()+15000,scope.attributes().get("deadlineAt") instanceof Number n?n.longValue():Long.MAX_VALUE);
        var identity=new LinkedHashMap<String,Object>(Map.of("tenantId",scope.tenantId(),"userId",scope.userId(),"scopeAuthority","API_AUTHENTICATION_CONTEXT","invocationSource","sql_studio_metadata"));
        if(scope.runId()!=null)identity.put("runId",scope.runId());
        var result=kernel.execute(new McpServiceCall(null,scope.requestId(),serviceId,name,arguments,identity,deadline));
        if(result==null || !result.successful()){
            boolean denied=result!=null&&(result.status()==McpServiceResultStatus.REJECTED || Objects.toString(result.errorMessage(),"").contains("SecurityException:"));
            log.warn("SQL metadata reference failed service={} tool={} status={} errorCode={}",serviceId,name,
                result==null?"NO_RESPONSE":result.status(),result==null?"NO_RESPONSE":result.errorCode());
            String details=result==null?"":Objects.toString(result.errorMessage(),"")+Objects.toString(result.data(),"");
            if(!denied&&(details.contains("QUERY_BATCH_BUDGET_EXCEEDED")||details.contains("Too many requests in one retrieval")))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"METADATA_QUERY_BUDGET: Narrow the business concept and retry; existing catalog retrieval budget was exceeded");
            throw new ResponseStatusException(denied?HttpStatus.FORBIDDEN:HttpStatus.SERVICE_UNAVAILABLE,
                denied?"METADATA_ACCESS_DENIED":"METADATA_SERVICE_UNAVAILABLE");
        }
        return structured(result.data(),0);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> structured(Object value,int depth){
        if(!(value instanceof Map<?,?> map)||depth>4)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid structured metadata response");
        if(map.containsKey("evidenceBundle")||map.containsKey("templates")||map.containsKey("results"))return (Map<String,Object>)map;
        for(String key:List.of("structuredContent","structured_content","data","result"))if(map.get(key) instanceof Map<?,?>)return structured(map.get(key),depth+1);
        return (Map<String,Object>)map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object value, int depth) {
        if (!(value instanceof Map<?, ?> map) || depth > 4) return Map.of();
        if (CONTRACT.equals(map.get("contractVersion"))) return (Map<String, Object>) map;
        for (String key : List.of("structuredContent", "structured_content", "data", "result")) {
            Map<String, Object> nested = payload(map.get(key), depth + 1);
            if (!nested.isEmpty()) return nested;
        }
        return Map.of();
    }
}
