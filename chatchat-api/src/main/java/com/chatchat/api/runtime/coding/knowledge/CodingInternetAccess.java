package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.model.PlatformInternetPolicy;
import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.common.retrieval.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.CodingTaskContract;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Live intersection of model switch, frozen Provider revision and current Agent/user grants. */
@Service
public class CodingInternetAccess {
    public static final Set<String> TOOLS=Set.of("web_search","web_fetch");
    private final PlatformInternetPolicy models;private final AgentResourceScopePort agents;private final McpControlPlaneClient mcp;private final ObjectMapper json;
    public CodingInternetAccess(PlatformInternetPolicy models,AgentResourceScopePort agents,McpControlPlaneClient mcp,ObjectMapper json){this.models=models;this.agents=agents;this.mcp=mcp;this.json=json;}
    public Map<String,Object> snapshot(KernelDataScope scope,String agent,String model,boolean requested){
        var policy=models.read(model);if(!policy.enabled()||!models.active(model)||agent==null||agent.isBlank()||!agents.resolve(scope.tenantId(),scope.userId(),agent,requested).contains(AgentResourcePolicy.INTERNET))return Map.of();
        List<Map<String,Object>> selected=new ArrayList<>();
        try{
            var available=models.providers();
            for(String id:policy.providerIds()){
                var provider=provider(available,id);
                if(provider==null||!provider.path("enabled").asBoolean()||!provider.path("configured").asBoolean())return Map.of();
                selected.add(Map.of("id",id,"revision",provider.path("revision").asLong(),"name",provider.path("name").asText(id)));
            }
        }
        catch(RuntimeException unavailable){
            org.slf4j.LoggerFactory.getLogger(CodingInternetAccess.class).warn("Internet Provider control plane unavailable; no Internet capability admitted");
            return Map.of();
        }
        if(selected.isEmpty())return Map.of();
        return Map.of("schemaVersion","runtime_os.internet.v1","modelName",model,"modelRevision",policy.revision(),"providerId",policy.providerId(),"providerRevision",selected.get(0).get("revision"),"providers",selected,"agentId",agent,"explicitlyRequested",requested);
    }
    private JsonNode provider(JsonNode available,String id){for(var item:available)if(id.equals(item.path("id").asText()))return item;return null;}
    private List<? extends Map<?,?>> bindings(Map<?,?> profile){
        if(profile.get("providers") instanceof List<?> list)return list.stream().map(p->(Map<?,?>)p).toList();
        return List.of(Map.of("id",profile.get("providerId"),"revision",profile.get("providerRevision")));
    }
    public void require(CodingTaskContract c,String tool){
        if(!TOOLS.contains(tool)||!c.authorization().allowedTools().contains(tool)||c.authorization().networkPolicy()!=CodingTaskContract.NetworkPolicy.NONE
            ||!(c.runtimeContext().get("internetSearch") instanceof Map<?,?> p))throw new SecurityException("Internet tool outside task authorization");
        var model=c.runtimeContext().get("model") instanceof Map<?,?> m?m:Map.of();
        if(!Objects.equals(model.get("selectedModel"),p.get("modelName")))throw new SecurityException("Internet model binding changed");
        String name=Objects.toString(p.get("modelName"),""),agent=Objects.toString(p.get("agentId"),"");var live=models.read(name);
        if(!live.enabled()||!models.active(name)||live.revision()!=((Number)p.get("modelRevision")).longValue()||!Objects.equals(live.providerId(),p.get("providerId")))throw new SecurityException("Model Internet authorization revoked or changed");
        if(!agents.resolve(c.scope().tenantId(),c.scope().userId(),agent,Boolean.TRUE.equals(p.get("explicitlyRequested"))).contains(AgentResourcePolicy.INTERNET))throw new SecurityException("Agent/user Internet authorization revoked");
        var bindings=bindings(p);
        if(!live.providerIds().equals(bindings.stream().map(b->b.get("id")).toList()))throw new SecurityException("Search Provider selection changed");
        var available=models.providers();
        for(var binding:bindings){
            var provider=provider(available,Objects.toString(binding.get("id"),""));
            if(provider==null||!provider.path("enabled").asBoolean()||!provider.path("configured").asBoolean()
                ||provider.path("revision").asLong()!=((Number)binding.get("revision")).longValue())throw new SecurityException("Search Provider authorization revoked or changed");
        }
    }
    public Map<String,Object> read(CodingTaskContract c,String tool,String call,JsonNode args){
        require(c,tool);var profile=(Map<?,?>)c.runtimeContext().get("internetSearch");
        String selected=args.has("provider_id")?args.path("provider_id").asText():Objects.toString(profile.get("providerId"),"");
        var binding=bindings(profile).stream().filter(p->selected.equals(p.get("id"))).findFirst().orElseThrow(()->new SecurityException("Search Provider outside model selection"));
        var arguments=args.deepCopy();if(arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode object)object.remove("provider_id");
        var request=new LinkedHashMap<String,Object>();request.put("providerId",selected);request.put("providerRevision",binding.get("revision"));
        request.put("tenantId",c.scope().tenantId());request.put("userId",c.scope().userId());request.put("agentId",profile.get("agentId"));request.put("taskId",c.taskId());
        request.put("tool",tool);request.put("requestId",com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(c.taskId()+"\0"+tool+"\0"+call));request.put("arguments",arguments);
        var result=mcp.request("POST","/api/v1/internet-search/read",json.valueToTree(request));
        require(c,tool); // A switch/grant revoked during IO cannot disclose a late response.
        if(!result.isObject())throw new IllegalStateException("Invalid Internet MCP result");
        return json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
    }
}
