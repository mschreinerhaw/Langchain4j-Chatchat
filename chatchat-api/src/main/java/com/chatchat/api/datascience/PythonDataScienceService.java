package com.chatchat.api.datascience;

import com.chatchat.integration.mcp.service.McpPythonControlPlaneClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class PythonDataScienceService {
    private final PythonAssetRepository assetRepository;
    private final PythonScriptRepository scriptRepository;
    private final PythonScriptVersionRepository versionRepository;
    private final PythonTemplateRepository templateRepository;
    private final PythonExecutionRepository executionRepository;
    private final McpPythonControlPlaneClient mcp;
    private final PythonTemplateIndexService indexService;
    private final PythonTemplateToolRegistry registry;
    private final ObjectMapper objectMapper;

    public Workbench workbench(String tenant,String owner){return new Workbench(assetRepository.findByTenantIdAndOwnerIdOrderByCreatedAtDesc(tenant,owner),scriptRepository.findByTenantIdAndOwnerIdOrderByUpdatedAtDesc(tenant,owner),executionRepository.findTop50ByTenantIdAndOwnerIdOrderByStartedAtDesc(tenant,owner));}
    public List<McpPythonControlPlaneClient.EnvironmentView> publishedEnvironments(){return mcp.environments();}

    @Transactional
    public PythonAssetEntity createAsset(String tenant,String owner,AssetRequest request){
        if(request==null)throw new IllegalArgumentException("环境配置不能为空");requireText(request.name(),"环境名称不能为空");requireText(request.environmentId(),"必须选择 MCP 已发布环境");
        var env=mcp.environment(request.environmentId());
        PythonAssetEntity asset=new PythonAssetEntity();asset.setTenantId(tenant);asset.setOwnerId(owner);asset.setName(request.name().trim());asset.setDescription(trim(request.description()));asset.setMcpEnvironmentId(env.id());asset.setMcpEnvironmentVersion(env.versionNumber());asset.setDockerImage(env.dockerImage());asset.setPythonVersion(env.pythonVersion());asset.setCpuLimit(env.cpuLimit());asset.setMemoryLimit(env.memoryLimit());asset.setNetworkEnabled(env.networkEnabled());asset.setDependenciesJson("[]");asset.setStatus("CREATING");asset=assetRepository.saveAndFlush(asset);
        var result=mcp.provision(env.id(),tenant,owner,asset.getId());asset.setContainerName(result.containerName());asset.setWorkspacePath(result.workspacePath());asset.setStatus(result.ready()?"READY":"DISABLED");asset.setStatusMessage(result.ready()?"MCP 隔离环境已就绪":result.message());return assetRepository.save(asset);
    }

    @Transactional
    public PythonScriptEntity saveScript(String tenant,String owner,ScriptRequest request){
        PythonAssetEntity asset=ownedReadyAsset(request.assetId(),tenant,owner);requireText(request.fileName(),"脚本文件名不能为空");requireText(request.sourceCode(),"脚本代码不能为空");String file=request.fileName().trim();if(!file.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,170}\\.py"))throw new IllegalArgumentException("脚本文件名必须是安全的 .py 文件名");
        PythonScriptEntity script=request.id()==null||request.id().isBlank()?scriptRepository.findByAssetIdAndFileName(asset.getId(),file).orElseGet(PythonScriptEntity::new):ownedScript(request.id(),tenant,owner);if(script.getId()==null){script.setTenantId(tenant);script.setOwnerId(owner);script.setAssetId(asset.getId());script.setCurrentVersion(0);}script.setFileName(file);script.setTitle(or(request.title(),file));script.setSourceCode(request.sourceCode());script.setStatus("DRAFT");script.setLastTestSucceeded(false);script.setCurrentVersion(script.getCurrentVersion()+1);script=scriptRepository.saveAndFlush(script);
        PythonScriptVersionEntity version=new PythonScriptVersionEntity();version.setScriptId(script.getId());version.setVersionNumber(script.getCurrentVersion());version.setSourceCode(script.getSourceCode());version.setSourceHash(sha256(script.getSourceCode()));versionRepository.save(version);return script;
    }

    @Transactional
    public PythonExecutionEntity testScript(String tenant,String owner,String scriptId,Map<String,Object> parameters){PythonScriptEntity script=ownedScript(scriptId,tenant,owner);PythonAssetEntity asset=ownedReadyAsset(script.getAssetId(),tenant,owner);var result=mcp.preview(asset.getMcpEnvironmentId(),tenant,owner,asset.getId(),script.getFileName(),script.getSourceCode(),parameters);PythonExecutionEntity execution=recordExecution(asset,script.getId(),null,tenant,owner,parameters,result);if("SUCCEEDED".equals(execution.getStatus())){script.setStatus("TESTED");script.setLastTestSucceeded(true);script.setLastTestedAt(Instant.now());scriptRepository.save(script);}return execution;}

    @Transactional
    public PythonTemplateEntity publish(String tenant,String owner,String scriptId,PublishRequest request){
        PythonScriptEntity script=ownedScript(scriptId,tenant,owner);PythonAssetEntity asset=ownedReadyAsset(script.getAssetId(),tenant,owner);if(!script.isLastTestSucceeded()||!"TESTED".equals(script.getStatus()))throw new IllegalArgumentException("脚本必须先在 MCP 环境成功测试后才能发布");requireText(request.templateName(),"模板名称不能为空");requireText(request.scenario(),"场景描述是发布和检索的必填依据");requireText(request.description(),"功能描述不能为空");validateJsonObject(request.inputSchema(),"输入 Schema");validateJsonObject(request.outputSchema(),"输出 Schema");
        PythonTemplateEntity t=new PythonTemplateEntity();t.setTenantId(tenant);t.setOwnerId(owner);t.setAssetId(asset.getId());t.setScriptId(script.getId());t.setScriptVersion(script.getCurrentVersion());t.setTemplateName(request.templateName().trim());t.setVersion(or(request.version(),"1.0.0"));t.setScenario(request.scenario().trim());t.setDescription(request.description().trim());t.setKeywords(trim(request.keywords()));t.setDomain(trim(request.domain()));t.setInputSchemaJson(or(request.inputSchema(),"{}"));t.setOutputSchemaJson(or(request.outputSchema(),"{}"));t.setSourceSnapshot(script.getSourceCode());t.setSearchText(searchText(t));t.setStatus("PUBLISHING");t.setIndexStatus("PENDING");t.setRuntimeStatus("PENDING");t.setMcpSyncStatus("PENDING");t.setToolName(toolName(t.getTemplateName()));t=templateRepository.saveAndFlush(t);
        try{var synced=mcp.synchronizeTemplate(t.getId(),new McpPythonControlPlaneClient.TemplatePayload(tenant,owner,asset.getId(),asset.getMcpEnvironmentId(),t.getTemplateName(),t.getToolName(),t.getVersion(),t.getScenario(),t.getDescription(),t.getKeywords(),t.getDomain(),t.getInputSchemaJson(),t.getOutputSchemaJson(),script.getSourceCode()));t.setMcpSyncStatus("SYNCED");t.setMcpSyncMessage("MCP tool: "+synced.toolName());t.setRuntimeStatus("READY");}catch(RuntimeException ex){t.setStatus("DISABLED");t.setRuntimeStatus("DISABLED");t.setMcpSyncStatus("FAILED");t.setMcpSyncMessage(ex.getMessage());templateRepository.save(t);throw new IllegalStateException("MCP 模板同步失败："+ex.getMessage(),ex);}
        PythonTemplateIndexService.IndexResult indexed=indexService.index(t);if(!indexed.success()){mcp.setTemplateEnabled(t.getId(),false);t.setStatus("DISABLED");t.setIndexStatus("FAILED");t.setRuntimeStatus("DISABLED");templateRepository.save(t);throw new IllegalStateException("模板索引失败："+indexed.message());}t.setStatus("PUBLISHED");t.setIndexStatus(indexed.mode());t=templateRepository.save(t);registry.register(t);return t;
    }

    public List<PythonTemplateEntity> templates(String tenant){return templateRepository.findByTenantIdOrderByPublishedAtDesc(tenant);}
    public List<PythonScriptVersionEntity> versions(String tenant,String owner,String scriptId){ownedScript(scriptId,tenant,owner);return versionRepository.findByScriptIdOrderByVersionNumberDesc(scriptId);}
    public List<PythonTemplateIndexService.SearchHit> search(String tenant,String query,int limit){return indexService.search(tenant,query,Math.max(1,Math.min(limit,50)));}

    @Transactional public PythonExecutionEntity executeTemplate(String tenant,String owner,String templateId,Map<String,Object> parameters){PythonTemplateEntity t=templateRepository.findByIdAndTenantId(templateId,tenant).orElseThrow(()->new IllegalArgumentException("Python 模板不存在"));if(!"PUBLISHED".equals(t.getStatus())||!"READY".equals(t.getRuntimeStatus())||!"SYNCED".equals(t.getMcpSyncStatus()))throw new IllegalArgumentException("Python 模板当前不可执行");PythonAssetEntity asset=assetRepository.findById(t.getAssetId()).orElseThrow(()->new IllegalArgumentException("模板运行环境不存在"));return recordExecution(asset,null,t.getId(),tenant,owner,parameters,mcp.executeTemplate(t.getId(),parameters));}
    @Transactional public PythonTemplateEntity setTemplateEnabled(String tenant,String templateId,boolean enabled){PythonTemplateEntity t=templateRepository.findByIdAndTenantId(templateId,tenant).orElseThrow(()->new IllegalArgumentException("Python 模板不存在"));mcp.setTemplateEnabled(t.getId(),enabled);if(enabled){PythonTemplateIndexService.IndexResult result=indexService.index(t);if(!result.success()){mcp.setTemplateEnabled(t.getId(),false);throw new IllegalStateException("重新索引失败："+result.message());}t.setStatus("PUBLISHED");t.setIndexStatus(result.mode());t.setRuntimeStatus("READY");t.setMcpSyncStatus("SYNCED");templateRepository.save(t);registry.register(t);}else{t.setStatus("DISABLED");t.setRuntimeStatus("DISABLED");t.setIndexStatus("REMOVED");templateRepository.save(t);indexService.remove(t.getId());registry.unregister(t);}return t;}

    private PythonExecutionEntity recordExecution(PythonAssetEntity asset,String scriptId,String templateId,String tenant,String owner,Map<String,Object> params,McpPythonControlPlaneClient.ExecutionResult r){PythonExecutionEntity e=new PythonExecutionEntity();e.setTenantId(tenant);e.setOwnerId(owner);e.setAssetId(asset.getId());e.setScriptId(scriptId);e.setTemplateId(templateId);e.setParametersJson(json(params==null?Map.of():params));e.setStatus(r.status());e.setExitCode(r.exitCode());e.setStdout(r.stdout());e.setStderr(r.stderr());e.setDurationMs(r.durationMs());e.setFinishedAt(Instant.now());e.setResultJson(r.stdout());return executionRepository.save(e);}
    private PythonAssetEntity ownedReadyAsset(String id,String tenant,String owner){PythonAssetEntity asset=assetRepository.findByIdAndTenantIdAndOwnerId(id,tenant,owner).orElseThrow(()->new IllegalArgumentException("Python Asset 不存在或不属于当前用户"));if(!"READY".equals(asset.getStatus()))throw new IllegalArgumentException("只有 READY 状态的 Python Asset 才能开发和发布");return asset;}
    private PythonScriptEntity ownedScript(String id,String tenant,String owner){return scriptRepository.findByIdAndTenantIdAndOwnerId(id,tenant,owner).orElseThrow(()->new IllegalArgumentException("Python 脚本不存在或不属于当前用户"));}
    private String searchText(PythonTemplateEntity t){return "模板名称：\n"+t.getTemplateName()+"\n\n使用场景：\n"+t.getScenario()+"\n\n功能：\n"+t.getDescription()+"\n\n关键词：\n"+t.getKeywords()+"\n\n领域：\n"+t.getDomain()+"\n\n输入：\n"+t.getInputSchemaJson()+"\n\n输出：\n"+t.getOutputSchemaJson();}
    private String toolName(String name){String slug=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");if(slug.isBlank())slug="python_template";return "python_"+slug+"_"+UUID.randomUUID().toString().substring(0,8);}
    private void validateJsonObject(String value,String label){try{JsonNode node=objectMapper.readTree(or(value,"{}"));if(!node.isObject())throw new IllegalArgumentException(label+" 必须是 JSON 对象");}catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException(label+" 不是合法 JSON");}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(Exception ex){throw new IllegalArgumentException("JSON 序列化失败",ex);}}
    private String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void requireText(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);}private String trim(String value){return value==null?"":value.trim();}private String or(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}

    public record Workbench(List<PythonAssetEntity> assets,List<PythonScriptEntity> scripts,List<PythonExecutionEntity> executions){}
    public record AssetRequest(String name,String description,String environmentId){}
    public record ScriptRequest(String id,String assetId,String fileName,String title,String sourceCode){}
    public record PublishRequest(String templateName,String scenario,String description,String keywords,String domain,String version,String inputSchema,String outputSchema){}
}
