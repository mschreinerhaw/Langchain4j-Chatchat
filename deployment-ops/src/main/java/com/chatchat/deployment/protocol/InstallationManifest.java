package com.chatchat.deployment.protocol;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.*;
import java.security.SecureRandom;

/** Executable signed installation contract shared by the generic and native adapters. */
public final class InstallationManifest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern PARAMETER=Pattern.compile("\\$\\$\\{([A-Za-z][A-Za-z0-9_]*)}|\\$\\{([A-Za-z][A-Za-z0-9_]*)}");
    private InstallationManifest(){}
    public static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    public static String name(String value){require(value!=null&&value.matches("[a-z][a-z0-9-]{0,39}"),"Use a lowercase name, max 40 characters");return value;}
    public static String member(String value){require(value!=null&&value.matches("[A-Za-z0-9_./-]{1,240}")&&!value.startsWith("/")&&!Arrays.asList(value.split("/",-1)).stream().anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")),"Unsafe archive member");return value;}
    public static String containerPath(String value){require(value!=null&&value.startsWith("/")&&!value.equals("/")&&value.length()<=240&&!Arrays.asList(value.split("/")).contains(".."),"Invalid container path");for(String sensitive:List.of("/proc","/sys","/dev","/var/run","/run/docker.sock"))require(!value.equals(sensitive)&&!value.startsWith(sensitive+"/"),"Sensitive container path forbidden");return value;}
    static void fields(JsonNode value,String allowed){require(value.isObject(),"Object required");Set<String> keys=Set.of(allowed.split(","));value.fieldNames().forEachRemaining(k->require(keys.contains(k),"Unknown protocol field: "+k));}
    public static List<JsonNode> validate(JsonNode manifest,Set<String> files){
        fields(manifest,"schemaVersion,name,version,description,platform,requirements,parameters,images,volumes,components,upgradeFrom,dataCompatibility,links,runtimeInstaller");
        require(manifest.path("schemaVersion").asInt()==1,"Unsupported installation schema");name(manifest.path("name").asText());
        require(manifest.path("version").asText().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"),"Invalid release version");
        require(manifest.path("platform").asText().matches("linux/(amd64|arm64|arm)"),"Unsupported target platform");
        JsonNode images=manifest.path("images");require(images.isObject()&&images.size()>0&&images.size()<=100,"1..100 pinned images required");
        images.fields().forEachRemaining(entry->{name(entry.getKey());JsonNode image=entry.getValue();fields(image,"id,archive,size");require(image.path("id").asText().matches("sha256:[a-f0-9]{64}"),"Immutable image ID required");require(files.contains(member(image.path("archive").asText())),"Unsigned image archive");require(image.path("size").isIntegralNumber()&&image.path("size").asLong()>=0,"Image size required");});
        JsonNode parameters=manifest.path("parameters");require(parameters.isMissingNode()||(parameters.isObject()&&parameters.size()<=100),"Invalid parameter schema");
        parameters.fields().forEachRemaining(entry->{require(entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}"),"Invalid parameter name");JsonNode p=entry.getValue();fields(p,"type,label,description,default,required,secret,generate,enum,pattern,min,max");require(Set.of("string","integer","boolean").contains(p.path("type").asText("string")),"Invalid parameter type");require(!p.path("secret").asBoolean()||!p.has("default"),"Secret defaults are forbidden");if(p.has("pattern")){require(p.path("pattern").asText().length()<=200,"Parameter pattern too long");Pattern.compile(p.path("pattern").asText());}});
        manifest.path("volumes").fields().forEachRemaining(e->{name(e.getKey());fields(e.getValue(),"description");});
        JsonNode components=manifest.path("components");require(components.isArray()&&components.size()>0&&components.size()<=60,"1..60 components required");
        Map<String,JsonNode> byId=new LinkedHashMap<>();
        for(JsonNode c:components){
            fields(c,"id,image,kind,dependsOn,environment,entrypoint,command,mounts,files,ports,health,memoryBytes,cpus,when,retrySafe,runOn,dataChange,user,capabilities,networkFrom,schemaSensitive");
            String id=name(c.path("id").asText());require(byId.putIfAbsent(id,c)==null,"Duplicate component");String kind=c.path("kind").asText("service");require(Set.of("service","job","external").contains(kind),"Invalid component kind");
            require(kind.equals("external")||images.has(c.path("image").asText()),"Component image missing from signed inventory");
            require(!c.has("dependsOn")||c.path("dependsOn").isArray(),"Dependency array required");
            for(String field:List.of("mounts","files","ports","capabilities"))require(!c.has(field)||c.path(field).isArray(),"Array required: "+field);
            for(String field:List.of("command","entrypoint"))if(c.has(field)){require(c.path(field).isArray()&&c.path(field).size()<=100,"Command argv array required");for(JsonNode argument:c.path(field))require(argument.isTextual()&&argument.asText().length()<=65536,"Invalid command argument");}
            for(JsonNode mount:c.path("mounts")){fields(mount,"volume,target,readOnly");require(manifest.path("volumes").has(mount.path("volume").asText()),"Unknown owned volume");containerPath(mount.path("target").asText());}
            for(JsonNode asset:c.path("files")){fields(asset,"source,target,template,mode,uid,gid");require(files.contains(member(asset.path("source").asText())),"Unsigned configuration asset");containerPath(asset.path("target").asText());require(asset.path("mode").asText("0644").matches("0?[0-7]{3}"),"Invalid file permissions");}
            for(JsonNode capability:c.path("capabilities"))require(Set.of("CHOWN","FOWNER","SETUID","SETGID","DAC_OVERRIDE","NET_BIND_SERVICE","IPC_LOCK").contains(capability.asText()),"Unsafe capability");
            if(c.has("user"))require(c.path("user").asText().matches("[0-9]{1,6}(:[0-9]{1,6})?"),"Numeric container user required");
            if(c.has("networkFrom")){boolean found=false;for(JsonNode dependency:c.path("dependsOn"))found|=dependency.asText().equals(c.path("networkFrom").asText());require(found,"Network sharing requires an owned dependency");}
            if(kind.equals("service"))require(Set.of("http","tcp","docker").contains(c.path("health").path("type").asText()),"Every service needs a health gate");
            if(kind.equals("job"))require(Set.of("install","always").contains(c.path("runOn").asText("install")),"Invalid initialization lifecycle");
        }
        List<JsonNode> order=new ArrayList<>();Set<String> done=new HashSet<>();
        while(done.size()<byId.size()){
            int before=done.size();for(var entry:byId.entrySet()){if(done.contains(entry.getKey()))continue;boolean ready=true;for(JsonNode dependency:entry.getValue().path("dependsOn")){require(byId.containsKey(dependency.asText()),"Unknown dependency");ready&=done.contains(dependency.asText());}if(ready){order.add(entry.getValue());done.add(entry.getKey());}}
            require(done.size()>before,"Component dependency cycle");
        }
        for(JsonNode link:manifest.path("links")){fields(link,"name,url");parameters.fields().forEachRemaining(e->{if(e.getValue().path("secret").asBoolean())require(!link.path("url").asText().contains("${"+e.getKey()+"}"),"Secret in application link");});}
        require(Set.of("initial-only","backward-compatible").contains(manifest.path("dataCompatibility").asText("initial-only")),"Invalid data compatibility");
        if(manifest.has("runtimeInstaller")){JsonNode adapter=manifest.path("runtimeInstaller");fields(adapter,"protocol,image,imageMap,services,schemaHashes,sourceCommit,contractVersion,stagePlan");require(manifest.path("name").asText().equals("runtime-os")&&adapter.path("protocol").asText().equals("runtime-os-native/v1"),"Invalid native adapter");require(images.has(adapter.path("image").asText())&&files.contains(member(adapter.path("imageMap").asText())),"Unsigned native installer");require(adapter.path("services").isArray()&&adapter.path("services").size()>0&&adapter.path("services").size()<=64,"Native service inventory required");Set<String> serviceNames=new HashSet<>();for(JsonNode service:adapter.path("services"))require(serviceNames.add(name(service.asText())),"Duplicate native service");if(adapter.has("contractVersion")){require(adapter.path("contractVersion").asInt()==1&&serviceNames.equals(byId.keySet()),"Native contract inventory mismatch");for(JsonNode c:components)require(c.has("entrypoint")&&c.has("command")&&c.has("dependsOn")&&c.path("entrypoint").size()+c.path("command").size()>0,"Native contract requires explicit startup argv and dependencies");}require(adapter.path("schemaHashes").isObject()&&adapter.path("schemaHashes").size()>0,"Schema identities required");adapter.path("schemaHashes").fields().forEachRemaining(e->require(e.getValue().asText().matches("[a-f0-9]{64}"),"Invalid schema identity"));}
        return order;
    }
    public static ObjectNode parameters(JsonNode schema,JsonNode provided,JsonNode previous){
        require(provided.isObject(),"Parameter object required");provided.fieldNames().forEachRemaining(k->require(schema.has(k),"Unknown parameter: "+k));ObjectNode result=JSON.createObjectNode();
        schema.fields().forEachRemaining(entry->{String key=entry.getKey();JsonNode field=entry.getValue(),value=provided.get(key);
            if(value==null||value.isNull()||(field.path("secret").asBoolean()&&value.asText().isEmpty())){value=previous==null?null:previous.get(key);if(value==null)value=field.get("default");if(value==null&&field.path("generate").asBoolean()){byte[] random=new byte[24];new SecureRandom().nextBytes(random);value=TextNode.valueOf(Base64.getUrlEncoder().withoutPadding().encodeToString(random));}}
            if(value==null){require(!field.path("required").asBoolean(),"Required parameter: "+key);value=TextNode.valueOf("");}
            switch(field.path("type").asText("string")){
                case "integer"->{require(value.isIntegralNumber()||(value.isTextual()&&value.asText().matches("-?[0-9]+")),"Integer required: "+key);long number=Long.parseLong(value.asText());require(number>=field.path("min").asLong(-9007199254740992L)&&number<=field.path("max").asLong(9007199254740992L),"Parameter out of range: "+key);value=LongNode.valueOf(number);}
                case "boolean"->require(value.isBoolean(),"Boolean required: "+key);
                default->{require(value.isTextual()&&value.asText().length()<=65536,"String required: "+key);if(field.has("pattern"))require(Pattern.matches(field.path("pattern").asText(),value.asText()),"Parameter format invalid: "+key);}
            }
            if(field.has("enum")){boolean found=false;for(JsonNode allowed:field.path("enum"))found|=allowed.equals(value);require(found,"Choose an allowed value: "+key);}result.set(key,value);
        });return result;
    }
    public static JsonNode render(JsonNode value,JsonNode parameters){
        if(value.isTextual()){Matcher match=PARAMETER.matcher(value.asText());StringBuffer output=new StringBuffer();while(match.find()){if(match.group(1)!=null)match.appendReplacement(output,Matcher.quoteReplacement("${"+match.group(1)+"}"));else{require(parameters.has(match.group(2)),"Unknown template parameter: "+match.group(2));match.appendReplacement(output,Matcher.quoteReplacement(parameters.path(match.group(2)).asText()));}}match.appendTail(output);return TextNode.valueOf(output.toString());}
        if(value.isArray()){ArrayNode out=JSON.createArrayNode();for(JsonNode item:value)out.add(render(item,parameters));return out;}
        if(value.isObject()){ObjectNode out=JSON.createObjectNode();value.fields().forEachRemaining(e->out.set(e.getKey(),render(e.getValue(),parameters)));return out;}
        return value.deepCopy();
    }
    public static ObjectNode publicParameters(JsonNode schema,JsonNode values){ObjectNode result=JSON.createObjectNode();values.fields().forEachRemaining(e->{if(!schema.path(e.getKey()).path("secret").asBoolean())result.set(e.getKey(),e.getValue());});return result;}
}
