package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.controller.mcp.McpControlPlaneClient;
import com.chatchat.common.retrieval.AgentResourcePolicy;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Fixed-origin delegation to existing MCP data engines. No semantic review or model call. */
@Service
public class CodingResourceReader {
    public static final Set<String> TOOLS=Set.of("news_search","news_get_article","news_related_events","market_search","market_get_history","market_get_indicators","market_get_snapshot");
    private final McpControlPlaneClient mcp;
    private final ObjectMapper json;
    public CodingResourceReader(McpControlPlaneClient mcp,ObjectMapper json){this.mcp=mcp;this.json=json;}
    public record Read(String tool,JsonNode arguments) { }
    public List<Map<String,Object>> readBatch(List<Read> reads) {
        if(reads.isEmpty())return List.of();
        if(reads.size()>16)throw new IllegalArgumentException("Source validation batch exceeds budget");
        for(var read:reads)if(!Set.of("news_get_article","market_get_dataset","market_get_history","market_get_indicators","market_get_snapshot").contains(read.tool())
            ||read.arguments()==null||!read.arguments().isObject()||read.arguments().toString().length()>10000)throw new SecurityException("Invalid source validation operation");
        var response=mcp.request("POST","/api/v1/resource-exploration/read-batch",json.valueToTree(Map.of("requests",reads)));
        if(!response.isArray()||response.size()!=reads.size())throw new IllegalStateException("Invalid source validation response");
        var result=new ArrayList<Map<String,Object>>();
        for(var item:response) {
            if(!item.isObject())throw new IllegalStateException("Invalid source validation item");
            var value=json.convertValue(item,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
            value.remove("authority");value.remove("license_status");result.add(value);
        }
        return result;
    }
    public static String resource(String tool) {
        if(tool.startsWith("knowledge_"))return AgentResourcePolicy.KNOWLEDGE;
        if(tool.startsWith("news_"))return AgentResourcePolicy.NEWS;
        if(tool.startsWith("market_"))return AgentResourcePolicy.MARKET;
        throw new SecurityException("Unknown resource tool");
    }
    public Map<String,Object> read(String tool,JsonNode args) {
        if(!TOOLS.contains(tool)&&!tool.equals("market_get_dataset"))throw new SecurityException("Unknown resource read");
        var response=mcp.request("POST","/api/v1/resource-exploration/read",json.valueToTree(Map.of("tool",tool,"arguments",args)));
        if(!response.isObject())throw new IllegalStateException("Invalid resource response");
        var result=json.convertValue(response,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        result.remove("authority");result.remove("license_status");return result;
    }
}
