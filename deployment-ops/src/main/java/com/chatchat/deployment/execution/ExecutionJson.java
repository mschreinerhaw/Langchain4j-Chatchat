package com.chatchat.deployment.execution;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.net.URLEncoder;
import java.util.*;

public final class ExecutionJson {
    private ExecutionJson(){}
    public static ObjectNode object(){return StateFiles.JSON.createObjectNode();}
    public static ArrayNode array(){return StateFiles.JSON.createArrayNode();}
    public static String url(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    public static String fingerprint(JsonNode value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(StateFiles.JSON.writeValueAsBytes(canonical(value))));}catch(Exception error){throw new IllegalStateException(error);}}
    public static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode out=object();List<String> names=new ArrayList<>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String name:names)out.set(name,canonical(value.get(name)));return out;}if(value.isArray()){ArrayNode out=array();for(JsonNode item:value)out.add(canonical(item));return out;}return value;}
    public static JsonNode redact(JsonNode value){
        if(value.isObject()){ObjectNode out=object();value.fields().forEachRemaining(e->out.set(e.getKey(),e.getKey().matches("(?i).*?(password|secret|token|credential|authorization).*|(?i)Env")?TextNode.valueOf("[REDACTED]"):redact(e.getValue())));return out;}
        if(value.isArray()){ArrayNode out=array();for(int i=0;i<value.size();i++){boolean secret=i>0&&value.get(i-1).asText().matches("--?(password|token|secret|credential|api-key)");out.add(secret?TextNode.valueOf("[REDACTED]"):redact(value.get(i)));}return out;}
        if(value.isTextual())return TextNode.valueOf(value.asText().replaceAll("(?i)((?:password|token|secret|credential|api[_-]?key)\\s*[=:]\\s*)[^\\s,;]+","$1[REDACTED]"));return value;
    }
}
