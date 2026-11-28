package com.chatchat.agents.orchestration.analysis.graph;

import java.math.BigDecimal;
import java.util.*;

/** Explicit JSON Schema subset for typed batch results. Unsupported contracts fail closed. */
public final class WorkspaceResultSchema {
    private static final Set<String> KEYS = Set.of("type", "properties", "required", "items", "minItems", "enum", "additionalProperties", "description", "title");
    public static void checkContract(Map<String,Object> schema) {
        checkContract(schema, 0);
    }
    private static void checkContract(Map<String,Object> schema, int depth) {
        if (depth > 32 || schema.size() > 128) throw new IllegalArgumentException("Output schema exceeds structural limits");
        if (!KEYS.containsAll(schema.keySet())) throw new IllegalArgumentException("Unsupported outputSchema keyword");
        if (!(schema.get("type") instanceof String type) || !Set.of("object","array","string","number","integer","boolean","null").contains(type))
            throw new IllegalArgumentException("outputSchema requires a supported type");
        if (schema.containsKey("enum") && !(schema.get("enum") instanceof List<?>)) throw new IllegalArgumentException("enum must be an array");
        if(schema.containsKey("minItems")&&(!"array".equals(type)||!(schema.get("minItems") instanceof Number n)||n.doubleValue()<0||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue()))throw new IllegalArgumentException("minItems must be a nonnegative integer on an array");
        if ("object".equals(type)) {
            Map<String,Object> properties = object(schema.getOrDefault("properties", Map.of()));
            if (properties.size() > 128) throw new IllegalArgumentException("Too many output properties");
            properties.values().forEach(value -> checkContract(object(value), depth + 1));
            if (schema.containsKey("required") && (!(schema.get("required") instanceof List<?> required) || !properties.keySet().containsAll(required)))
                throw new IllegalArgumentException("required must name declared properties");
            if (schema.containsKey("additionalProperties") && !(schema.get("additionalProperties") instanceof Boolean))
                throw new IllegalArgumentException("additionalProperties must be boolean");
        }
        if ("array".equals(type)) checkContract(object(schema.get("items")), depth + 1);
    }
    public static void validate(Object value, Map<String,Object> schema) {
        validate(value,schema,"");
    }
    public static void validate(Object value, Map<String,Object> schema, String path) {
        String type = String.valueOf(schema.get("type"));
        boolean valid = switch(type) {
            case "object" -> value instanceof Map<?,?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number && new BigDecimal(value.toString()).stripTrailingZeros().scale() <= 0;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
        if (!valid) throw violation(path,"TYPE_MISMATCH","expected "+type);
        if (schema.get("enum") instanceof List<?> options && !options.contains(value))
            throw violation(path,"ENUM_MISMATCH",enumAction(options));
        if (value instanceof Map<?,?> values && "object".equals(type)) {
            var properties = object(schema.getOrDefault("properties", Map.of()));
            var required = (List<?>)schema.getOrDefault("required", List.of());
            var missing=required.stream().filter(key->!values.containsKey(key))
                .map(key->pointer(path,key)).limit(8).toList();
            if(!missing.isEmpty())throw violation(path,"MISSING_REQUIRED_FIELDS","add "+missing);
            if (Boolean.FALSE.equals(schema.get("additionalProperties")) && !properties.keySet().containsAll(values.keySet()))
                throw violation(path,"UNDECLARED_FIELDS","remove keys absent from the frozen schema at this object");
            properties.forEach((key,rule) -> {
                if(values.containsKey(key))validate(values.get(key),object(rule),pointer(path,key));
            });
        }
        if (value instanceof List<?> values && "array".equals(type)) {
            if(schema.get("minItems") instanceof Number minimum&&values.size()<minimum.longValue())
                throw violation(path,"MIN_ITEMS","expected at least "+minimum+" items");
            var itemSchema=object(schema.get("items"));
            for(int i=0;i<values.size();i++) validate(values.get(i),itemSchema,pointer(path,i));
        }
    }
    private static String enumAction(List<?> options){
        if(!options.isEmpty()&&options.size()<=16&&options.stream().allMatch(option->
            option instanceof String text&&text.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,95}")))
            return "use an exact value from "+options;
        return "use a value from the frozen schema";
    }
    private static String pointer(String path,Object field){
        return path+"/"+field.toString().replace("~","~0").replace("/","~1");
    }
    private static IllegalArgumentException violation(String path,String code,String action){
        return new IllegalArgumentException(code+" at "+(path.isEmpty()?"<root>":path)+" - "+action);
    }
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> raw)) throw new IllegalArgumentException("Expected an object");
        var result = new LinkedHashMap<String,Object>();
        raw.forEach((key,item) -> result.put(String.valueOf(key),item));
        return result;
    }
}
