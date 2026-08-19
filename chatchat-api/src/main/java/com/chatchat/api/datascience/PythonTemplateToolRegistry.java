package com.chatchat.api.datascience;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j @Component @RequiredArgsConstructor
public class PythonTemplateToolRegistry {
    private final ToolRegistry toolRegistry;
    private final PythonTemplateRepository repository;
    private final ObjectProvider<PythonDataScienceService> serviceProvider;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void registerPublished(){ repository.findByStatus("PUBLISHED").forEach(this::register); }

    public void register(PythonTemplateEntity template){
        ToolMetadata metadata=ToolMetadata.builder().id(template.getToolName()).title(template.getTemplateName()).description(template.getScenario()+"\n"+template.getDescription()).version(template.getVersion()).author(template.getOwnerId()).categories(List.of("数据科学","Python模板")).category("python_data_science").riskLevel("low").operationType("read").runtimeLevel("readonly").parameters(parameters(template.getInputSchemaJson())).outputType("json").timeoutMillis(300_000L).agentCompatible(true).tags(keywords(template.getKeywords())).metadata(Map.of("assetType","PYTHON_TEMPLATE","templateId",template.getId(),"assetId",template.getAssetId(),"runtime","PYTHON")).build();
        toolRegistry.registerTool(template.getToolName(),metadata,new ToolRegistry.EnhancedTool(){
            public ToolMetadata getMetadata(){return metadata;}
            public ToolOutput execute(ToolInput input){
                try{ Map<String,Object> params=parameters(input); PythonExecutionEntity result=serviceProvider.getObject().executeTemplate(template.getTenantId(),input==null?template.getOwnerId():or(input.getUserId(),template.getOwnerId()),template.getId(),params); Map<String,Object> output=new LinkedHashMap<>(); output.put("executionId",result.getId()); output.put("status",result.getStatus()); output.put("stdout",result.getStdout()); output.put("stderr",result.getStderr()); output.put("exitCode",result.getExitCode()); output.put("durationMs",result.getDurationMs()); return result.getExitCode()!=null&&result.getExitCode()==0?ToolOutput.success(output):ToolOutput.failure(result.getStderr()); }
                catch(Exception ex){ return ToolOutput.failure(ex); }
            }
        });
    }
    public void unregister(PythonTemplateEntity template){ if(template!=null&&template.getToolName()!=null) toolRegistry.unregisterTool(template.getToolName()); }
    private List<ToolParameter> parameters(String schemaJson){
        try{ JsonNode root=objectMapper.readTree(schemaJson); Set<String> required=new HashSet<>(); root.path("required").forEach(v->required.add(v.asText())); List<ToolParameter> result=new ArrayList<>(); root.path("properties").fields().forEachRemaining(entry->{ JsonNode value=entry.getValue(); result.add(ToolParameter.builder().name(entry.getKey()).type(value.path("type").asText("string")).description(value.path("description").asText()).required(required.contains(entry.getKey())).build()); }); return result; }
        catch(Exception ex){ log.warn("Cannot parse Python template schema: {}",ex.getMessage()); return List.of(); }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> parameters(ToolInput input)throws Exception{ if(input==null)return Map.of(); if(input.getParameters()!=null&&!input.getParameters().isEmpty())return input.getParameters(); if(input.getRawInput()!=null&&!input.getRawInput().isBlank())return objectMapper.readValue(input.getRawInput(),Map.class); return Map.of(); }
    private List<String> keywords(String value){ return value==null?List.of():Arrays.stream(value.split("[,，;；\\s]+" )).filter(v->!v.isBlank()).distinct().limit(20).toList(); }
    private String or(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
}
