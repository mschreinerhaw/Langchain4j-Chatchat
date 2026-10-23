package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.mcpserver.templatepublication.policy.TemplateQueryToolNamePolicy;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Released Schema is enforced on the existing parent-delegated execution path. */
@Component @RequiredArgsConstructor
public class ToolReleaseContractCatalog {
    private final ToolReleaseRepository releases;
    private final ObjectMapper json;
    public Optional<ToolRelease> active(String toolName) {
        return releases.findByState("RUNNING").stream().filter(row -> !TemplateCapabilityService.KIND.equals(row.getKind())).filter(row -> {
            try { return toolName.equals(TemplateQueryToolNamePolicy.toolName(json.readTree(row.getDefinitionJson()).path("domainCode").asText())); }
            catch(Exception ex) { throw new IllegalStateException("Invalid released tool definition",ex); }
        }).findFirst();
    }
    public McpSchema.JsonSchema inputSchema(String toolName,McpSchema.JsonSchema fallback) {
        return active(toolName).map(row -> {
            JsonNode schema=read(row.getInputSchemaJson());
            Map<String,Object> props=schema.has("properties") ? json.convertValue(schema.get("properties"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}):Map.of();
            List<String> required=new ArrayList<>();schema.path("required").forEach(item -> required.add(item.asText()));
            Map<String,Object> defs=schema.has("$defs")?json.convertValue(schema.get("$defs"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}):null;
            return new McpSchema.JsonSchema("object",props==null?Map.of():props,required,
                !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean(),defs,null);
        }).orElse(fallback);
    }
    public Map<String,Object> outputSchema(String toolName) {
        return active(toolName).map(row -> json.convertValue(read(row.getOutputSchemaJson()),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})).orElse(null);
    }
    public Map<String,Object> metadata(String toolName) {
        return active(toolName).map(row -> Map.<String,Object>of("logicalToolId",row.getLogicalId(),"releaseId",row.getId(),
            "toolVersion",row.getReleaseNumber()+".0.0","publicationStatus","active",
            "breakingChangeApproved",true)).orElse(Map.of());
    }
    public String title(String name,String fallback) { return active(name).map(ToolRelease::getTitle).orElse(fallback); }
    public String description(String name,String fallback) {
        return active(name).map(ToolRelease::getDescription).filter(value -> !value.isBlank()).map(value -> value+"\n\n"+fallback).orElse(fallback);
    }
    public void validateInput(String toolName,Map<String,Object> value) {
        active(toolName).ifPresent(row -> {
            var arguments=new LinkedHashMap<String,Object>(value==null?Map.of():value);
            // Runtime envelope and parent delegation are transport fields, outside the public tool Schema.
            for(String key:Set.of("_templateQueryChildToolName","mcpContext","tenantId","userId","username","roles",
                "traceId","workspaceId","env","domain","permissionLevel","scopeExpression"))arguments.remove(key);
            var schema=read(row.getInputSchemaJson());
            if(!schema.path("properties").has("assetType"))arguments.remove("assetType");
            ToolReleaseSchemas.validate(schema,json.valueToTree(arguments));
        });
    }
    public void validateOutput(String toolName,Map<String,Object> value) {
        active(toolName).ifPresent(row -> ToolReleaseSchemas.validate(read(row.getOutputSchemaJson()),json.valueToTree(value)));
    }
    private JsonNode read(String source) { try{return json.readTree(source);}catch(Exception ex){throw new IllegalStateException(ex);} }
}
