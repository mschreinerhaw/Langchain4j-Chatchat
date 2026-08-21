package com.chatchat.integration.mcp.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import java.time.Duration;
import java.util.*;

@Service @RequiredArgsConstructor
public class McpPythonControlPlaneClient {
    private final McpCenterProperties properties;
    private final InternalCredentialProperties credentials;
    private final ObjectMapper mapper;
    private final WebClient webClient=WebClient.builder().exchangeStrategies(ExchangeStrategies.builder()
        .codecs(c->c.defaultCodecs().maxInMemorySize(64*1024*1024)).build()).build();

    public List<EnvironmentView> environments(){Object data=get("/api/v1/python/environments?published=true");if(!(data instanceof List<?> list))return List.of();return list.stream().filter(Map.class::isInstance).map(item->mapper.convertValue(item,EnvironmentView.class)).toList();}
    public EnvironmentView environment(String id){return environments().stream().filter(e->Objects.equals(e.id(),id)).findFirst().orElseThrow(()->new IllegalArgumentException("MCP 已发布 Python 环境不存在："+id));}
    public ProvisionResult provision(String environmentId,String tenantId,String ownerId,String assetId){return convert(post("/api/v1/python/runtime/assets/provision",Map.of("environmentId",environmentId,"tenantId",tenantId,"ownerId",ownerId,"assetId",assetId)),ProvisionResult.class);}
    public ExecutionResult preview(String environmentId,String tenantId,String ownerId,String assetId,String fileName,String source,Map<String,Object> parameters){Map<String,Object> body=new LinkedHashMap<>();body.put("environmentId",environmentId);body.put("tenantId",tenantId);body.put("ownerId",ownerId);body.put("assetId",assetId);body.put("fileName",fileName);body.put("sourceCiphertext",encrypt(source));body.put("parameters",parameters==null?Map.of():parameters);return convert(post("/api/v1/python/runtime/preview",body),ExecutionResult.class);}
    public SyncResult synchronizeTemplate(String id,TemplatePayload payload){Map<String,Object> body=mapper.convertValue(payload,new TypeReference<>(){});body.put("sourceCiphertext",encrypt(payload.source()));body.remove("source");return convert(put("/api/v1/python/templates/"+encode(id),body),SyncResult.class);}
    public ExecutionResult executeTemplate(String id,String tenantId,String ownerId,Map<String,Object> parameters){String token=login();Object raw=webClient.post().uri(url("/api/v1/python/templates/"+encode(id)+"/execute")).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).header("X-Tenant-Scope",scopeHeader(tenantId)).header("X-Owner-Scope",scopeHeader(ownerId)).contentType(MediaType.APPLICATION_JSON).bodyValue(parameters==null?Map.of():parameters).retrieve().bodyToMono(Object.class).timeout(timeout()).block();return convert(unwrap(raw),ExecutionResult.class);}
    public void setTemplateEnabled(String id,boolean enabled){post("/api/v1/python/templates/"+encode(id)+"/enabled?enabled="+enabled,Map.of());}
    public DataFileResult uploadDataFile(String tenantId,String ownerId,String fileId,String fileName,String fileHash,byte[] content){
        byte[] encrypted=InternalSecretCipher.encryptBytes(content,secret());String token=login();
        Object raw=webClient.post().uri(url("/api/v1/python/data-files/"+encode(fileId)))
            .header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
            .header("X-Tenant-Scope",scopeHeader(tenantId)).header("X-Owner-Scope",scopeHeader(ownerId))
            .header("X-File-Name",Base64.getUrlEncoder().withoutPadding().encodeToString(fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .header("X-File-Sha256",fileHash).contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(encrypted).retrieve().bodyToMono(Object.class).timeout(timeout()).block();
        return convert(unwrap(raw),DataFileResult.class);
    }
    public byte[] downloadDataFile(String tenantId,String ownerId,String fileId){String token=login();byte[] encrypted=webClient.get().uri(url("/api/v1/python/data-files/"+encode(fileId)+"/content"))
        .header(HttpHeaders.AUTHORIZATION,"Bearer "+token).header("X-Tenant-Scope",scopeHeader(tenantId)).header("X-Owner-Scope",scopeHeader(ownerId))
        .retrieve().bodyToMono(byte[].class).timeout(timeout()).block();return InternalSecretCipher.decryptBytes(encrypted,secret());}
    public void deleteDataFile(String tenantId,String ownerId,String fileId){String token=login();webClient.delete().uri(url("/api/v1/python/data-files/"+encode(fileId)))
        .header(HttpHeaders.AUTHORIZATION,"Bearer "+token).header("X-Tenant-Scope",scopeHeader(tenantId)).header("X-Owner-Scope",scopeHeader(ownerId))
        .retrieve().bodyToMono(Object.class).timeout(timeout()).block();}
    private String encrypt(String source){return InternalSecretCipher.encrypt(source,secret());}
    private String secret(){String secret=credentials.resolvedSecret();if(secret.isBlank())throw new IllegalStateException("内部加密凭据未配置，禁止传输数据");return secret;}
    private String scopeHeader(String value){if(value==null||value.isBlank()||value.length()>256)throw new IllegalArgumentException("非法内部数据作用域");return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private Object get(String path){return exchange("GET",path,null);}private Object post(String path,Object body){return exchange("POST",path,body);}private Object put(String path,Object body){return exchange("PUT",path,body);}
    private Object exchange(String method,String path,Object body){String token=login();WebClient.RequestBodySpec request=webClient.method(org.springframework.http.HttpMethod.valueOf(method)).uri(url(path)).header(HttpHeaders.AUTHORIZATION,"Bearer "+token).accept(MediaType.APPLICATION_JSON);Object raw=(body==null?request.retrieve():request.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve()).bodyToMono(Object.class).timeout(timeout()).block();return unwrap(raw);}
    private String login(){Map<String,String> body=Map.of("username",properties.resolvedAdminUsername(credentials),"password",properties.resolvedAdminPassword(credentials));Object raw=webClient.post().uri(url(properties.getAdminLoginPath())).contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(Object.class).timeout(timeout()).block();Object data=unwrap(raw);if(!(data instanceof Map<?,?> map)||map.get("token")==null)throw new IllegalStateException("MCP 管理端登录未返回 token");return String.valueOf(map.get("token"));}
    private Object unwrap(Object raw){if(raw instanceof Map<?,?> map&&map.containsKey("code")){int code=Integer.parseInt(String.valueOf(map.get("code")));if(code!=200)throw new IllegalStateException(String.valueOf(map.get("message")));return map.get("data");}return raw;}
    private <T>T convert(Object data,Class<T> type){return mapper.convertValue(data,type);}private String url(String path){String base=properties.getBaseUrl()==null?"":properties.getBaseUrl().replaceAll("/+$","");return base+(path.startsWith("/")?path:"/"+path);}private Duration timeout(){return Duration.ofMillis(properties.getTimeoutMs()>0?properties.getTimeoutMs():300_000);}private String encode(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    public record EnvironmentView(String id,String name,String description,String dockerImage,String pythonVersion,String cpuLimit,String memoryLimit,String diskLimit,String tmpfsLimit,String runtimeUser,String networkPolicy,String networkName,String requirementsJson,int timeoutSeconds,boolean networkEnabled,int versionNumber,String status){}
    public record ProvisionResult(boolean ready,String containerName,String workspacePath,String message){}
    public record ExecutionResult(String id,String containerId,String status,String stdout,String stderr,Integer exitCode,Long durationMs){}
    public record SyncResult(String id,String status,String toolName,String environmentId,String sourceHash){}
    public record DataFileResult(String id,String storagePath,String pythonPath,long fileSize,String fileHash,String status){}
    public record TemplatePayload(String tenantId,String ownerId,String assetId,String assetName,String assetDescription,String environmentId,String templateName,String toolName,String version,String scenario,String description,String keywords,String domain,String inputSchemaJson,String outputSchemaJson,String source){}
}
