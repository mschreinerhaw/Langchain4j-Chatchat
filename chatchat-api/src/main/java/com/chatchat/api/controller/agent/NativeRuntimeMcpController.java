package com.chatchat.api.controller.agent;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.coding.*;
import com.chatchat.api.runtime.coding.nativecline.*;
import com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.chatchat.agents.runtime.coding.CodingTaskAccess.Action.*;

/** Internal stateless MCP facade; the fixed Node broker supplies a signed task
 * grant. Caller-supplied tenant, provider or Agent IDs never grant authority. */
@RestController
@RequestMapping("/internal/runtime/native-coding")
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
public class NativeRuntimeMcpController {
    private final NativeS3Settings settings;private final ObjectMapper json;private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;private final CodingInternetAccess internet;private final CodingSkillReader skills;
    private final NativeS3Evidence evidence;
    public NativeRuntimeMcpController(NativeS3Settings settings,ObjectMapper json,CodingInvocationContext identity,DurableCodingTaskService tasks,CodingInternetAccess internet,CodingSkillReader skills,NativeS3Evidence evidence){this.settings=settings;this.json=json;this.identity=identity;this.tasks=tasks;this.internet=internet;this.skills=skills;this.evidence=evidence;}
    private JsonNode verify(String header){
        try{
            if(header==null||!header.startsWith("Bearer ")||header.length()>32768)throw new SecurityException();
            var parts=header.substring(7).split("\\.",-1);if(parts.length!=2)throw new SecurityException();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.issuerKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(parts[0].getBytes(StandardCharsets.UTF_8)),Base64.getUrlDecoder().decode(parts[1])))throw new SecurityException();
            var grant=json.readTree(Base64.getUrlDecoder().decode(parts[0]));if(grant.path("version").asInt()!=2||grant.path("expiresAt").asLong()<=System.currentTimeMillis())throw new SecurityException();return grant;
        }catch(Exception invalid){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Native task capability");}
    }
    private <T>T authorized(String header,java.util.function.Function<CodingTaskContract,T> action){
        var grant=verify(header);var scope=new KernelDataScope(grant.path("tenantId").asText(),grant.path("userId").asText(),null,null,grant.path("deliveryBinding").path("runId").asText(),null,Map.of());String task=grant.path("taskId").asText();
        try{return identity.as(scope,Set.of(READ,WORKER,EVIDENCE),()->{
            var contract=tasks.contract(scope,task);var binding=tasks.privateState(scope,task,"native-s3:binding");
            if(!NativeSiloTaskService.supports(contract)||binding==null||!binding.path("grant").asText().equals(header.substring(7)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Task capability binding differs");
            requireActive(contract);var value=action.apply(contract);requireActive(contract);return value;
        });}catch(CodingContractException denied){
            if(Set.of(CodingErrorCode.NOT_FOUND,CodingErrorCode.AUTHORIZATION_DENIED).contains(denied.error().code()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Task outside this capability scope");
            throw denied;
        }
    }
    private void requireActive(CodingTaskContract c){var outcome=tasks.get(c.scope(),c.taskId());if(outcome.status().terminal()||outcome.executionControl().stopReason()!=null||!c.budget().deadlineAt().isAfter(java.time.Instant.now()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Task is closed");}
    @PostMapping("/mcp") public ResponseEntity<?> mcp(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody JsonNode rpc){
        return authorized(auth,c->{
            if(!rpc.isObject()||rpc.toString().length()>16000||!rpc.path("jsonrpc").asText().equals("2.0"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bounded JSON-RPC 2.0 request required");
            String method=rpc.path("method").asText();if(method.equals("notifications/initialized"))return ResponseEntity.accepted().build();
            if(!(rpc.path("id").isTextual()||rpc.path("id").isIntegralNumber())||rpc.path("id").asText().length()>128)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bounded RPC request identity required");
            Object result;
            if(method.equals("initialize"))result=Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","runtime-authorized-tools","version","1.0.0"));
            else if(method.equals("ping"))result=Map.of();
            else if(method.equals("tools/list")){
                var list=new ArrayList<Map<String,Object>>();for(String tool:List.of("web_search","web_fetch","web_download"))if(c.authorization().allowedTools().contains(tool)){
                    String field=tool.equals("web_search")?"query":"url";
                    list.add(Map.of("name",tool,"description",tool.equals("web_download")?"Download an authorized public PDF/CSV/JSON/TXT/HTML into this task workspace; returns its local path, source and hash.":tool.equals("web_search")?"Search public Internet sources through the configured Runtime search provider.":"Read a public webpage through the Runtime SSRF-guarded fetch service.","inputSchema",schema(tool,c)));
                }result=Map.of("tools",list);
            }else if(method.equals("tools/call")){
                String tool=rpc.path("params").path("name").asText();if(!CodingInternetAccess.NATIVE_TOOLS.contains(tool))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tool outside Native Internet capability");
                var value=internet.read(c,tool,"native-mcp-"+rpc.path("id").asText(),rpc.path("params").path("arguments"));
                if(tool.equals("web_download") && "OK".equals(value.get("status"))){
                    byte[] data=Base64.getDecoder().decode(value.get("content").toString());
                    if(data.length!=((Number)value.get("size")).longValue()||!com.chatchat.api.assets.UserAssetService.hash(data).equals(value.get("sha256")))throw new SecurityException("Download integrity differs");
                    requireActive(c);
                    var file=evidence.assets.store(com.chatchat.api.assets.UserAssetService.Owner.of(c.scope()),com.chatchat.api.assets.UserAssetService.Kind.CONTEXT,c.taskId(),c.scope().runId(),"native-internet:"+value.get("sha256"),"download-"+value.get("sha256"),value.get("content_type").toString(),data);
                    value.put("assetId",file.id());
                }
                // Binary material is removed from model context by the trusted
                // Node broker after verifying the hash and staging local bytes.
                var audit=new LinkedHashMap<String,Object>(value);if(tool.equals("web_download"))audit.remove("content");evidence.archive(c,"internet-"+rpc.path("id").asText(),"internet-receipt.json",evidence.bytes(audit));
                result=Map.of("content",List.of(Map.of("type","text","text",new String(evidence.bytes(value),StandardCharsets.UTF_8))),"isError","ERROR".equals(value.get("status")));
            }else throw new ResponseStatusException(HttpStatus.FORBIDDEN,"MCP method not authorized");
            return ResponseEntity.ok(Map.of("jsonrpc","2.0","id",json.convertValue(rpc.path("id"),Object.class),"result",result));
        });
    }
    private Map<String,Object> schema(String tool,CodingTaskContract contract){
        String field=tool.equals("web_search")?"query":"url";
        var fields=new LinkedHashMap<String,Object>();fields.put(field,Map.of("type","string","description",field.equals("url")?"A single public HTTP/HTTPS URL (not a batch of requests)":"Public search query; do not disclose private data"));
        var profile=contract.runtimeContext().get("internetSearch");
        if(profile instanceof Map<?,?> map){
            var providerIds=new ArrayList<String>();
            if(map.get("providers") instanceof List<?> providers)for(Object raw:providers)if(raw instanceof Map<?,?> binding&&binding.get("id") instanceof String id)providerIds.add(id);
            if(providerIds.isEmpty()&&map.get("providerId") instanceof String id)providerIds.add(id);
            if(!providerIds.isEmpty())fields.put("provider_id",Map.of("type","string","enum",providerIds,"description","Optional selection among this task's already authorized search providers; omission uses its configured default"));
        }
        if(tool.equals("web_search")){
            fields.put("count",Map.of("type","integer","minimum",1,"maximum",10));
            fields.put("search_type",Map.of("type","string","enum",List.of("web","news")));
            fields.put("language",Map.of("type","string"));fields.put("time_range",Map.of("type","string","enum",List.of("day","week","month","year")));
        }else if(tool.equals("web_fetch"))fields.put("max_content_length",Map.of("type","integer","minimum",200,"maximum",20000));
        return Map.of("type","object","properties",fields,"required",List.of(field),"additionalProperties",false);
    }
    @org.springframework.beans.factory.annotation.Autowired private NativeSiloTaskService modelInputs;
    @PostMapping("/model") public ResponseEntity<?> model(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth){
        return authorized(auth,c->{
            if(!settings.dynamicModels)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dynamic model routing disabled");
            var grant=verify(auth);String selected=modelInputs.requireAuthorized(c).get("selectedModel").toString();
            var descriptor=settings.descriptor(selected);
            if(!grant.path("runtimeModel").equals(json.valueToTree(descriptor))||!grant.path("modelProfile").asText().equals(settings.profile(selected)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Published model binding changed");
            var connection=settings.connection(selected);if(!connection.get("model").equals(descriptor.get("providerModel")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Model publication changed during allocation");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(connection);
        });
    }
    @PostMapping("/skills") public Object skills(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth){return authorized(auth,c->{
        var packages=new ArrayList<Map<String,Object>>();var catalog=c.runtimeContext().get("skills");
        if(catalog instanceof Map<?,?> map&&map.get("skills") instanceof List<?> cards)for(Object raw:cards){
            var card=(Map<?,?>)raw;String id=card.get("id").toString();var lease=tasks.ownership(c.scope(),c.taskId());var main=skills.read(c,lease,id,"");var files=new ArrayList<Map<String,Object>>();
            files.add(Map.of("path","SKILL.md","content",main.get("instructions"),"encoding","utf8","sha256",main.get("contentSha256")));
            for(Object entry:(List<?>)main.get("resources")){var resource=(Map<?,?>)entry;files.add(skills.read(c,lease,id,resource.get("path").toString()));}
            packages.add(Map.of("id",id,"name",card.get("name"),"description",Objects.toString(card.get("description"), ""),"version",main.get("version"),"files",files));
        }
        return Map.of("packages",packages);
    });}
}
