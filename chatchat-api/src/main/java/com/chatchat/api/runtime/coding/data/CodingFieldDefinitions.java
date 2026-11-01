package com.chatchat.api.runtime.coding.data;

import java.util.*;

/** Carries producer-declared field semantics, never record values or inferred meanings. */
public final class CodingFieldDefinitions {
    private CodingFieldDefinitions() {}

    public static Map<String,Object> sourceFromContext(Map<String,Object> context) {
        return sanitizeSource(context.get("source"));
    }

    public static Map<String,Object> sanitizeSource(Object value) {
        if (!(value instanceof Map<?,?> source)) return Map.of();
        var result=new LinkedHashMap<String,Object>();
        for (String key:List.of("type","id","displayName","toolName","description")) {
            if (source.get(key) instanceof String text && !text.isBlank() && text.length()<=4096)
                result.put(key,text);
        }
        if(source.get("queryDefinition") instanceof String sql && !sql.isBlank() && sql.length()<=16000
            && "MCP_SQL_EXECUTION_RECEIPT".equals(source.get("queryDefinitionOrigin"))) {
            result.put("queryDefinition",sql);result.put("queryDefinitionOrigin","MCP_SQL_EXECUTION_RECEIPT");
        }
        if(source.get("resultComplete") instanceof Boolean complete)result.put("resultComplete",complete);
        if(source.get("returnedRowCount") instanceof Number count)result.put("returnedRowCount",count);
        return Map.copyOf(result);
    }

    public static Map<String,Object> fromContext(Map<String,Object> context) {
        Object schema=context.get("schema");
        if (!(schema instanceof Map<?,?> envelope)) return Map.of();
        Object definition=envelope.get("definition");
        if (!(definition instanceof Map<?,?> declared)) return Map.of();
        return sanitize(declared.get("properties"));
    }

    public static Map<String,Object> sanitize(Object value) {
        if (!(value instanceof Map<?,?> fields)) return Map.of();
        var result=new LinkedHashMap<String,Object>();
        for (var entry:fields.entrySet()) {
            if (result.size()>=256) break;
            if (!(entry.getKey() instanceof String name) || name.length()>256
                || !(entry.getValue() instanceof Map<?,?> declaration)) continue;
            var attributes=new LinkedHashMap<String,String>();
            for (String key:List.of("type","title","description","format","unit")) {
                if (declaration.get(key) instanceof String text && !text.isBlank() && text.length()<=2048)
                    attributes.put(key,text);
            }
            if (!attributes.isEmpty()) result.put(name,Map.copyOf(attributes));
        }
        return Map.copyOf(result);
    }
}
