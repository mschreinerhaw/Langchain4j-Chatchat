package com.chatchat.mcpserver.news.tool;

import com.chatchat.runtime.mcp.registry.*;
import com.chatchat.common.tool.*;
import com.chatchat.mcpserver.news.runtime.NewsRuntimeClient;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.time.Duration;

/** MCP atomic operations. Only the authenticated Runtime control plane supplies a task capability. */
@Component
public class InternetSearchMcpToolProvider implements McpToolProvider {
    private final NewsRuntimeClient news;private final ObjectMapper json;
    public InternetSearchMcpToolProvider(NewsRuntimeClient news,ObjectMapper json){this.news=news;this.json=json;}
    @Override public String capabilityCode(){return "news";}
    @Override public Collection<McpToolDefinition> definitions(){return List.of(
        definition("internet_web_search","query","Public Internet search using the task-authorized server Provider. Runtime task capability required; no automatic local/external fallback."),
        definition("internet_web_fetch","url","Fetch readable public page text through the SSRF-guarded service. Runtime task capability required; no direct code network or JavaScript execution."));}
    private McpToolDefinition definition(String name,String argument,String description){
        var schema=new LinkedHashMap<String,Object>();schema.put(argument,Map.of("type","string"));
        if(argument.equals("query")){schema.put("search_type",Map.of("type","string","enum",List.of("web","news")));schema.put("count",Map.of("type","integer","minimum",1,"maximum",10));schema.put("language",Map.of("type","string"));schema.put("time_range",Map.of("type","string","enum",List.of("day","week","month","year")));}
        else schema.put("max_content_length",Map.of("type","integer","minimum",200,"maximum",20000));
        return new McpToolDefinition(name,name,description,capabilityCode(),"chatchat-mcp-server",List.of(),true,false,Duration.ofSeconds(35),
            com.chatchat.common.mcp.contract.McpToolContractValidator.CONTRACT_VERSION,Map.of("type","object","properties",schema,"required",List.of(argument),"additionalProperties",false),Map.of(),com.chatchat.common.mcp.contract.McpToolGovernance.readOnly());
    }
    @Override public Optional<McpToolExecutor> findExecutor(String name){
        if(!Set.of("internet_web_search","internet_web_fetch").contains(name))return Optional.empty();
        return Optional.of(input->{
            if(!(input.getContext().get("codingInternet") instanceof Map<?,?> capability))return ToolOutput.failure("Runtime Internet task capability required");
            var request=new LinkedHashMap<String,Object>();capability.forEach((k,v)->request.put(k.toString(),v));
            request.put("tool",name.equals("internet_web_search")?"web_search":"web_fetch");request.put("arguments",input.getParameters());
            var value=read(json.valueToTree(request));var output=ToolOutput.success(value);
            if(value.path("status").asText().equals("ERROR")){output.setSuccess(false);output.setErrorMessage(value.path("errorCode").asText());}
            return output;
        });
    }
    public JsonNode read(JsonNode request){return news.post("/internet-search/read",request);}
}
