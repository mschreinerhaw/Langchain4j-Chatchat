package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.service.*;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Governance provider for SDK createMcpTools. Transport and connections stay in the existing directory. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeCodingMcpService {
    public record Binding(String serviceId,String agentId) {}
    public static final String CONTEXT="nativeMcp";
    private final McpServiceDirectory directory;
    private final ResourceAuthorizationPort authorization;
    private final DurableCodingTaskService tasks;
    private final NativeCodingWorkspaceService workspace;
    private final ObjectMapper json;
    public NativeCodingMcpService(McpServiceDirectory directory,ResourceAuthorizationPort authorization,
            DurableCodingTaskService tasks,NativeCodingWorkspaceService workspace,ObjectMapper json){
        this.directory=directory;this.authorization=authorization;this.tasks=tasks;this.workspace=workspace;this.json=json;
    }
    public List<Map<String,Object>> catalog(KernelDataScope scope,List<Binding> bindings){
        if(bindings.size()>16||bindings.stream().map(Binding::serviceId).distinct().count()!=bindings.size())
            throw new IllegalArgumentException("Invalid MCP service bindings");
        var result=new ArrayList<Map<String,Object>>();var names=new HashSet<String>();
        for(var binding:bindings){
            if(binding.serviceId()==null||binding.agentId()==null||binding.agentId().isBlank())throw new SecurityException("MCP Agent binding required");
            if(directory.services().stream().noneMatch(s->s.serviceId().equals(binding.serviceId())&&s.enabled()))
                throw new SecurityException("MCP service unavailable");
            for(var descriptor:directory.tools(new McpToolQuery(binding.serviceId(),null,Set.of()))){
                if(!allowed(scope,binding.agentId(),descriptor.localToolName()))continue;
                String name=descriptor.localToolName();
                if(!name.matches("[a-zA-Z0-9_-]{1,64}")||NativeCodeWorkspace.TOOLS.contains(name)||!names.add(name))
                    throw new SecurityException("MCP tool name conflicts with the task catalog");
                if(descriptor.inputSchema().isEmpty())throw new SecurityException("MCP input Schema unavailable");
                var card=new LinkedHashMap<String,Object>();card.put("serverName",binding.serviceId());card.put("agentId",binding.agentId());
                card.put("name",descriptor.remoteToolName());card.put("agentToolName",name);card.put("description",descriptor.description());
                card.put("inputSchema",descriptor.inputSchema());card.put("schemaSha256",CodingCanonicalHash.sha256Value(descriptor.inputSchema()));
                result.add(card);
                if(result.size()>64)throw new IllegalArgumentException("MCP catalog exceeds task envelope");
            }
        }
        return List.copyOf(result);
    }
    public static List<Map<String,Object>> cards(CodingTaskContract c){
        if(!(c.runtimeContext().get(CONTEXT) instanceof List<?> values))return List.of();
        var result=new ArrayList<Map<String,Object>>();
        for(Object value:values){
            if(!(value instanceof Map<?,?> map))throw new SecurityException("Invalid frozen MCP catalog");
            var card=new LinkedHashMap<String,Object>();map.forEach((k,v)->card.put(k.toString(),v));result.add(card);
        }
        return List.copyOf(result);
    }
    public static boolean contains(CodingTaskContract c,String tool){return cards(c).stream().anyMatch(v->tool.equals(v.get("agentToolName")));}
    public static List<Map<String,Object>> servers(CodingTaskContract c){return cards(c).stream().map(v->Map.<String,Object>of("serverName",v.get("serverName"))).distinct().toList();}
    private boolean allowed(KernelDataScope scope,String agent,String tool){
        return authorization.allowedIdsForAgent(ResourceAuthorizationPort.MCP_TOOL,scope.tenantId(),scope.userId(),Set.of(),Set.of(tool),agent).contains(tool);
    }
    public void requireLive(CodingTaskContract c,String tool){
        var card=cards(c).stream().filter(v->tool.equals(v.get("agentToolName"))).findFirst().orElseThrow(()->new SecurityException("MCP tool outside frozen catalog"));
        if(!c.authorization().allowedTools().contains(tool)||!allowed(c.scope(),(String)card.get("agentId"),tool))throw new SecurityException("MCP authorization revoked");
        String server=(String)card.get("serverName");
        if(directory.services().stream().noneMatch(s->s.serviceId().equals(server)&&s.enabled()))throw new SecurityException("MCP service disabled");
        var current=directory.findTool(server,tool).orElseThrow(()->new SecurityException("MCP tool unavailable"));
        if(!card.get("name").equals(current.remoteToolName())||!card.get("schemaSha256").equals(CodingCanonicalHash.sha256Value(current.inputSchema())))
            throw new SecurityException("MCP Schema changed; acquire a new task catalog");
    }
    public List<Map<String,Object>> list(CodingTaskContract c,CodingJournal.Lease lease,String server){
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);workspace.requireAuthorized(c);
        var result=new ArrayList<Map<String,Object>>();
        boolean bound=false;
        for(var card:cards(c))if(server.equals(card.get("serverName"))){
            bound=true;String tool=(String)card.get("agentToolName");
            if(!allowed(c.scope(),(String)card.get("agentId"),tool))continue;
            requireLive(c,tool);result.add(card);
        }
        if(!bound)throw new SecurityException("MCP server outside task catalog");
        return List.copyOf(result);
    }
    public Object call(CodingTaskContract c,CodingJournal.Lease lease,String server,String remote,String call,Map<String,Object> arguments){
        synchronized(workspace.ioLock(c)){
            tasks.requireWorkerLease(c.scope(),c.taskId(),lease,false);workspace.requireAuthorized(c);
            var matches=cards(c).stream().filter(v->server.equals(v.get("serverName"))&&remote.equals(v.get("name"))).toList();
            if(matches.size()!=1)throw new SecurityException("MCP tool outside task catalog");
            var card=matches.get(0);String tool=(String)card.get("agentToolName");requireLive(c,tool);
            var intent=workspace.intent(c,call);
            if(!tool.equals(intent.path("tool").asText())||!json.valueToTree(arguments).equals(jsonRead(intent.path("inputJson").asText())))
                throw new SecurityException("MCP arguments differ from the admitted model call");
            String operation=NativeCodingWorkspaceService.operation(c,call),key="native-mcp:"+operation;
            var previous=tasks.privateState(c.scope(),c.taskId(),key);
            if(previous!=null){
                if(previous.has("result"))return json.convertValue(previous.get("result"),Object.class);
                throw new IllegalStateException("MCP_RESULT_UNKNOWN: do not redispatch an unconfirmed operation");
            }
            tasks.privateState(c.scope(),c.taskId(),lease,key,null,json.valueToTree(Map.of("state","DISPATCHING","tool",tool)));
            var context=Map.<String,Object>of("tenantId",c.scope().tenantId(),"userId",c.scope().userId(),
                "authorizationAgentId",card.get("agentId"),"requestId",operation,"runId",c.scope().runId(),"taskId",c.taskId());
            // Existing transport owns reconnection. No wrapper retry of an uncertain side effect.
            var result=directory.invoke(new McpServiceCall(null,operation,server,tool,arguments,context,c.budget().deadlineAt().toEpochMilli()));
            if(!result.successful()&&(result.retryable()||Objects.toString(result.errorCode(),"").matches("(?i).*(TIMEOUT|TRANSPORT|CONNECTION|NETWORK).*")))
                throw new IllegalStateException("MCP_RESULT_UNKNOWN: transport did not confirm remote completion");
            Object output=result.rawData()!=null?result.rawData():result.data();
            if(output==null)output=Map.of("isError",!result.successful(),"status",result.status().name());
            var old=tasks.privateState(c.scope(),c.taskId(),key);
            tasks.privateState(c.scope(),c.taskId(),lease,key,old,json.valueToTree(Map.of("state","RETURNED","tool",tool,"result",output)));
            return output;
        }
    }
    private com.fasterxml.jackson.databind.JsonNode jsonRead(String value){try{return json.readTree(value);}catch(Exception invalid){throw new SecurityException("Invalid admitted MCP arguments",invalid);}}
}
