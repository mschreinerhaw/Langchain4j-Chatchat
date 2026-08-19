package com.chatchat.mcpserver.python;

import com.chatchat.mcpserver.tool.McpToolPublicationReviewer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j @Component @RequiredArgsConstructor
public class PythonMcpToolPublisher {
    private final ObjectProvider<McpSyncServer> serverProvider;
    private final PythonTemplateAssetRepository repository;
    private final ObjectProvider<PythonCapabilityService> serviceProvider;
    private final ObjectMapper objectMapper;
    private final Set<String> managed=ConcurrentHashMap.newKeySet();
    @Order(Ordered.LOWEST_PRECEDENCE) @EventListener(ApplicationReadyEvent.class) public void ready(){refresh();}
    public synchronized void refresh(){McpSyncServer server=serverProvider.getIfAvailable();if(server==null)return;managed.forEach(name->{try{server.removeTool(name);}catch(Exception ignored){}});managed.clear();for(PythonTemplate template:repository.findByStatus("PUBLISHED")){try{McpToolPublicationReviewer.addReviewedTool(server,spec(template));managed.add(template.getToolName());}catch(Exception ex){log.error("Python MCP tool publish failed id={} tool={}: {}",template.getId(),template.getToolName(),ex.getMessage(),ex);}}server.notifyToolsListChanged();}
    private McpServerFeatures.SyncToolSpecification spec(PythonTemplate t){McpSchema.Tool tool=McpSchema.Tool.builder().name(t.getToolName()).title(t.getTemplateName()).description(t.getScenario()+"\n"+t.getDescription()).inputSchema(schema(t.getInputSchemaJson())).meta(Map.of("assetType","PYTHON_TEMPLATE","runtime","PYTHON","templateId",t.getId(),"assetId",t.getAssetId(),"environmentId",t.getEnvironmentId(),"category","python_data_science","risk_level","low","operation_type","read","runtime_level","readonly")).build();return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange,request)->{PythonExecution result=serviceProvider.getObject().executeTemplate(t.getId(),request.arguments());Map<String,Object> structured=new LinkedHashMap<>();structured.put("executionId",result.getId());structured.put("status",result.getStatus());structured.put("stdout",result.getStdout());structured.put("stderr",result.getStderr());structured.put("exitCode",result.getExitCode());structured.put("durationMs",result.getDurationMs());return McpSchema.CallToolResult.builder().addTextContent(result.getStdout()==null?"":result.getStdout()).structuredContent(structured).isError(result.getExitCode()==null||result.getExitCode()!=0).build();}).build();}
    private McpSchema.JsonSchema schema(String json){try{Map<String,Object> s=objectMapper.readValue(json,new TypeReference<>(){});return new McpSchema.JsonSchema(String.valueOf(s.getOrDefault("type","object")),map(s.get("properties")),list(s.get("required")),s.get("additionalProperties") instanceof Boolean b?b:true,map(s.get("$defs")),map(s.get("definitions")));}catch(Exception ex){return new McpSchema.JsonSchema("object",Map.of(),List.of(),true,null,null);}}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object v){return v instanceof Map<?,?>?objectMapper.convertValue(v,new TypeReference<>(){}):Map.of();}private List<String> list(Object v){return v instanceof List<?> l?l.stream().map(String::valueOf).toList():List.of();}
}
