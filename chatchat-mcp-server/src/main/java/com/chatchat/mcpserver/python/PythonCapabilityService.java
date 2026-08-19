package com.chatchat.mcpserver.python;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class PythonCapabilityService {
    private final PythonEnvironmentRepository environments;
    private final PythonTemplateAssetRepository templates;
    private final PythonRuntimeExecutionRepository executions;
    private final PythonDockerRuntime runtime;
    private final InternalCredentialProperties credentials;
    private final PythonMcpToolPublisher publisher;
    private final PythonTemplateArgumentResolver argumentResolver;

    public List<PythonEnvironment> environments(boolean published){return published?environments.findByStatusOrderByNameAsc("PUBLISHED"):environments.findAllByOrderByUpdatedAtDesc();}
    @Transactional public PythonEnvironment saveEnvironment(EnvironmentRequest r){if(r==null||blank(r.name())||blank(r.dockerImage()))throw new IllegalArgumentException("环境名称和 Docker 镜像不能为空");PythonEnvironment e=blank(r.id())?new PythonEnvironment():environments.findById(r.id()).orElseThrow(()->new IllegalArgumentException("Python 环境不存在"));if(e.getStatus()!=null&&!"DRAFT".equals(e.getStatus()))throw new IllegalArgumentException("已发布过的环境不可修改，请创建新版本环境");e.setName(r.name().trim());e.setDescription(text(r.description()));e.setDockerImage(r.dockerImage().trim());e.setPythonVersion(or(r.pythonVersion(),"3.12"));e.setCpuLimit(or(r.cpuLimit(),"2"));e.setMemoryLimit(or(r.memoryLimit(),"4g"));e.setTimeoutSeconds(r.timeoutSeconds()==null?300:Math.max(1,r.timeoutSeconds()));e.setNetworkEnabled(Boolean.TRUE.equals(r.networkEnabled()));if(e.getStatus()==null)e.setStatus("DRAFT");return environments.save(e);}
    @Transactional public PythonEnvironment publishEnvironment(String id,boolean published){PythonEnvironment e=environment(id);e.setStatus(published?"PUBLISHED":"DISABLED");return environments.save(e);}
    public PythonDockerRuntime.ProvisionResult provision(ProvisionRequest r){PythonEnvironment env=publishedEnvironment(r.environmentId());return runtime.provision(env,required(r.tenantId(),"tenantId"),required(r.ownerId(),"ownerId"),required(r.assetId(),"assetId"));}
    @Transactional public PythonExecution preview(PreviewRequest r){PythonEnvironment env=publishedEnvironment(r.environmentId());String source=decrypt(r.sourceCiphertext());return execute(env,r.tenantId(),r.ownerId(),r.assetId(),null,or(r.fileName(),"preview.py"),source,r.parameters());}
    @Transactional public PythonTemplate synchronizeTemplate(String id,TemplateSyncRequest r){if(blank(id)||r==null)throw new IllegalArgumentException("模板同步数据不能为空");PythonEnvironment env=publishedEnvironment(r.environmentId());String source=decrypt(r.sourceCiphertext());PythonTemplate t=templates.findById(id).orElseGet(PythonTemplate::new);t.setId(id);t.setTenantId(required(r.tenantId(),"tenantId"));t.setOwnerId(required(r.ownerId(),"ownerId"));t.setAssetId(required(r.assetId(),"assetId"));t.setEnvironmentId(env.getId());t.setTemplateName(required(r.templateName(),"templateName"));t.setToolName(required(r.toolName(),"toolName"));t.setVersion(or(r.version(),"1.0.0"));t.setScenario(required(r.scenario(),"scenario"));t.setDescription(required(r.description(),"description"));t.setKeywords(text(r.keywords()));t.setDomain(text(r.domain()));t.setInputSchemaJson(or(r.inputSchemaJson(),"{}"));t.setOutputSchemaJson(or(r.outputSchemaJson(),"{}"));t.setSourceCiphertext(r.sourceCiphertext());t.setSourceHash(sha256(source));t.setStatus("PUBLISHED");t=templates.saveAndFlush(t);publisher.refresh();return t;}
    @Transactional public PythonTemplate setTemplateEnabled(String id,boolean enabled){PythonTemplate t=templates.findById(id).orElseThrow(()->new IllegalArgumentException("Python 模板不存在"));t.setStatus(enabled?"PUBLISHED":"DISABLED");t=templates.save(t);publisher.refresh();return t;}
    public List<PythonTemplate> templates(){return templates.findAllByOrderByUpdatedAtDesc();}
    @Transactional public PythonExecution executeTemplate(String id,Map<String,Object> params){PythonTemplate t=templates.findById(id).orElseThrow(()->new IllegalArgumentException("Python 模板不存在"));if(!"PUBLISHED".equals(t.getStatus()))throw new IllegalArgumentException("Python 模板未发布");Map<String,Object> resolved=argumentResolver.resolve(t.getInputSchemaJson(),params);return execute(publishedEnvironment(t.getEnvironmentId()),t.getTenantId(),t.getOwnerId(),t.getAssetId(),t.getId(),"template_"+t.getId()+".py",decrypt(t.getSourceCiphertext()),resolved);}
    @Transactional public PythonExecution executeTemplateForTenant(String id,String tenantId,Map<String,Object> params){PythonTemplate t=templates.findByIdAndTenantId(id,required(tenantId,"tenantId")).orElseThrow(()->new IllegalArgumentException("Python template does not exist in the current tenant"));return executeTemplate(t.getId(),params);}
    private PythonExecution execute(PythonEnvironment env,String tenant,String owner,String asset,String template,String file,String source,Map<String,Object> params){PythonExecution e=new PythonExecution();e.setTenantId(required(tenant,"tenantId"));e.setOwnerId(required(owner,"ownerId"));e.setAssetId(required(asset,"assetId"));e.setEnvironmentId(env.getId());e.setTemplateId(template);e.setStatus("RUNNING");e=executions.saveAndFlush(e);PythonDockerRuntime.ExecResult result=runtime.execute(env,tenant,owner,asset,file,source,params);e.setExitCode(result.exitCode());e.setStdout(result.stdout());e.setStderr(result.stderr());e.setDurationMs(result.durationMs());e.setFinishedAt(Instant.now());e.setStatus(result.exitCode()==0?"SUCCEEDED":result.timedOut()?"TIMED_OUT":"FAILED");return executions.save(e);}
    private PythonEnvironment environment(String id){return environments.findById(id).orElseThrow(()->new IllegalArgumentException("Python 环境不存在"));}private PythonEnvironment publishedEnvironment(String id){PythonEnvironment e=environment(id);if(!"PUBLISHED".equals(e.getStatus()))throw new IllegalArgumentException("Python 环境未发布或已停用");return e;}
    private String decrypt(String cipher){if(!InternalSecretCipher.isEncrypted(cipher))throw new IllegalArgumentException("脚本源码必须使用 AES-GCM 密文传输");return InternalSecretCipher.decryptIfNecessary(cipher,credentials.resolvedSecret());}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private String required(String v,String name){if(blank(v))throw new IllegalArgumentException(name+" 不能为空");return v.trim();}private boolean blank(String v){return v==null||v.isBlank();}private String text(String v){return v==null?"":v.trim();}private String or(String v,String f){return blank(v)?f:v.trim();}
    public record EnvironmentRequest(String id,String name,String description,String dockerImage,String pythonVersion,String cpuLimit,String memoryLimit,Integer timeoutSeconds,Boolean networkEnabled){}
    public record ProvisionRequest(String environmentId,String tenantId,String ownerId,String assetId){}
    public record PreviewRequest(String environmentId,String tenantId,String ownerId,String assetId,String fileName,String sourceCiphertext,Map<String,Object> parameters){}
    public record TemplateSyncRequest(String tenantId,String ownerId,String assetId,String environmentId,String templateName,String toolName,String version,String scenario,String description,String keywords,String domain,String inputSchemaJson,String outputSchemaJson,String sourceCiphertext){}
}
