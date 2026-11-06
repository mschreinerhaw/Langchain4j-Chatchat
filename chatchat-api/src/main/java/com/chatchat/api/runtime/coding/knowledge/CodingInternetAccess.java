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
    /** Diagnostic only: never performs a search or admits a capability missing from the frozen task. */
    public Map<String,Object> availability(KernelDataScope scope,String agent,String model,boolean requested,boolean admitted){
        if(admitted)return availabilityStatus("AVAILABLE","CONFIGURED_NOT_PROBED");
        if(model==null||model.isBlank())return availabilityStatus("UNKNOWN","MODEL_BINDING_UNAVAILABLE");
        try{
            if(!models.active(model))return availabilityStatus("UNAVAILABLE","MODEL_NOT_ACTIVE");
            var policy=models.read(model);
            if(!policy.enabled())return availabilityStatus("UNAVAILABLE","MODEL_INTERNET_DISABLED");
            if(agent==null||agent.isBlank())return availabilityStatus("UNAVAILABLE","AGENT_REQUIRED");
            if(!agents.resolve(scope.tenantId(),scope.userId(),agent,requested).contains(AgentResourcePolicy.INTERNET))
                return availabilityStatus("UNAVAILABLE","INTERNET_NOT_AUTHORIZED");
            var available=models.providers();
            if(policy.providerIds().isEmpty())return availabilityStatus("UNAVAILABLE","PROVIDER_NOT_CONFIGURED");
            for(String id:policy.providerIds()){
                var selected=provider(available,id);
                if(selected==null||!selected.path("configured").asBoolean())return availabilityStatus("UNAVAILABLE","PROVIDER_NOT_CONFIGURED");
                if(!selected.path("enabled").asBoolean())return availabilityStatus("UNAVAILABLE","PROVIDER_DISABLED");
            }
            return availabilityStatus("UNAVAILABLE","CAPABILITY_NOT_ADMITTED");
        }catch(RuntimeException unavailable){return availabilityStatus("UNKNOWN","CONFIGURATION_UNAVAILABLE");}
    }
    public static Map<String,Object> availabilityStatus(String status,String reason){
        return Map.of("schemaVersion","runtime_os.internet.availability.v1","status",status,
            "reasonCode",reason,"serviceChecked",false,"toolsAvailable","AVAILABLE".equals(status));
    }
    public Map<String,Object> snapshot(KernelDataScope scope,String agent,String model,boolean requested){
        var policy=models.read(model);if(!policy.enabled()||!models.active(model)||agent==null||agent.isBlank()||!agents.resolve(scope.tenantId(),scope.userId(),agent,requested).contains(AgentResourcePolicy.INTERNET))return Map.of();
        List<Map<String,Object>> selected=new ArrayList<>();
        try{
            var available=models.providers();
            for(String id:policy.providerIds()){
                var provider=provider(available,id);
                if(provider==null||!provider.path("enabled").asBoolean()||!provider.path("configured").asBoolean())return Map.of();
                boolean tencent="TENCENT".equals(provider.path("kind").asText());
                selected.add(Map.of("id",id,"revision",provider.path("revision").asLong(),"name",provider.path("name").asText(id),
                    "supportsNews",!tencent,"supportsLanguageFilter",!tencent));
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
    /** One fixed public query through the same provider quota/audit path; never changes configuration. */
    public Map<String,Object> probe(KernelDataScope scope,String agent,String model,String selected) {
        if(agent==null||agent.isBlank())return Map.of("status","ERROR","errorCode","AGENT_REQUIRED","serviceChecked",false);
        if(!models.active(model)||!models.read(model).enabled())return Map.of("status","ERROR","errorCode","MODEL_INTERNET_DISABLED","serviceChecked",false);
        if(!agents.resolve(scope.tenantId(),scope.userId(),agent,true).contains(AgentResourcePolicy.INTERNET))
            return Map.of("status","ERROR","errorCode","INTERNET_NOT_AUTHORIZED","serviceChecked",false);
        var frozen=snapshot(scope,agent,model,true);
        if(frozen.isEmpty())return Map.of("status","ERROR","errorCode","PROVIDER_NOT_CONFIGURED_OR_UNAVAILABLE","serviceChecked",false);
        String id=selected==null||selected.isBlank()?Objects.toString(frozen.get("providerId")):selected;
        var binding=bindings(frozen).stream().filter(p->id.equals(p.get("id"))).findFirst()
            .orElseThrow(()->new SecurityException("Probe provider outside model authorization"));
        String call="internet-probe-"+UUID.randomUUID();
        var request=new LinkedHashMap<String,Object>();request.put("providerId",id);request.put("providerRevision",binding.get("revision"));
        request.put("tenantId",scope.tenantId());request.put("userId",scope.userId());request.put("agentId",agent);request.put("taskId",call);
        request.put("tool","web_search");request.put("requestId",call);
        request.put("arguments",Map.of("query","stock exchange official security directory","search_type","web","count",1));
        JsonNode response;
        try{response=mcp.request("POST","/api/v1/internet-search/read",json.valueToTree(request));}
        catch(RuntimeException unavailable){return Map.of("status","ERROR","errorCode","SEARCH_SERVICE_UNAVAILABLE","serviceChecked",true);}
        if(!frozen.equals(snapshot(scope,agent,model,true)))throw new SecurityException("Probe authorization changed during request");
        var result=new LinkedHashMap<String,Object>();result.put("status",response.path("status").asText("ERROR"));
        result.put("errorCode",response.path("errorCode").asText(""));result.put("serviceChecked",true);
        result.put("providerId",id);result.put("requestId",call);result.put("retrievedAt",response.path("retrieved_at").asText());
        result.put("resultCount",response.path("count").asInt());result.put("queryHasResults",response.path("items").isArray()&&!response.path("items").isEmpty());
        result.put("authority","UNTRUSTED_SEARCH_SNIPPETS_NOT_VERIFIED_MARKET_DATA");
        return result;
    }
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
