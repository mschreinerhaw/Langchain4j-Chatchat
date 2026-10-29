package com.chatchat.api.datascience.python;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.runtime.coding.*;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.api.assets.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisService;
import com.chatchat.api.runtime.coding.cline.CodingCodeArtifactStore;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Source admission and projection only: existing file manager, Coding agent, evidence and Sandbox own execution. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="chatchat.assets",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding.analysis",name="enabled",havingValue="true")
public class PythonCodeReuseService {
    public record Actor(String tenant,String user,String legacyUser) { UserAssetService.Owner owner(){return new UserAssetService.Owner(tenant,user);} }
    public record Reference(String kind,String id,Integer version,String sha256,String path,String content) { }
    public record Prepare(String requestId,String instruction,String modelName,String entryPath,List<Reference> references,List<String> dataFileIds,List<String> skillIds) {
        public Prepare(String requestId,String instruction,String modelName,String entryPath,List<Reference> references,List<String> dataFileIds){this(requestId,instruction,modelName,entryPath,references,dataFileIds,List.of());}
    }
    public record Source(String kind,String id,int version,String sha256,String path,String content) { }
    public record Snapshot(String requestId,String instruction,String modelName,String entryPath,List<Source> sources,List<UserAssetService.File> dataFiles,List<com.chatchat.common.skills.DomainSkillRuntimePort.DomainSkillContent> skills) { }
    public record Execute(String snapshotSha256,boolean authorizeExecution) { }
    public record Apply(String operationId,String codeSha256,List<String> paths,String targetAssetId) { }
    private final PythonScriptRepository scripts;
    private final PythonScriptVersionRepository versions;
    private final PythonTemplateRepository templates;
    private final PythonScriptFolderRepository folders;
    private final PythonDataScienceService studio;
    private final PythonCodeAssistantService assistant;
    private final UserAssetService assets;
    private final AssetAnalysisInputs data;
    private final RuntimeExecutionCheckpointPort checkpoints;
    private final CodingAnalysisService coding;
    private final AgentChatModelResolver models;
    private final CodingInvocationContext identity;
    private final DurableCodingTaskService tasks;
    private final CodingAgentSPI agent;
    private final CodingCodeArtifactStore code;
    private final CodingEvidenceCollector collector;
    private final ObjectMapper json;
    private com.chatchat.api.runtime.coding.CodingParentModels parentModels;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setParentModels(com.chatchat.api.runtime.coding.CodingParentModels value){parentModels=value;}
    private static final String SNAPSHOT="python-studio:code-reuse:snapshot", RECEIPT="python-studio:code-reuse:receipt";
    private static final String APPLIED="python-studio:code-reuse:applied";

    public Map<String,Object> prepare(Actor actor,Prepare request) {
        if(request==null||request.instruction()==null||request.instruction().isBlank()||request.instruction().length()>6000
            ||request.references()==null||request.references().isEmpty()||request.references().size()>32)throw new IllegalArgumentException("Provide bounded code references and an analysis objective");
        var scope=scope(actor,request.requestId());
        var selected=new ArrayList<Source>();
        for(var reference:request.references())selected.add(resolve(actor,reference,scope));
        var project=PythonCodeProject.validate(new PythonCodeProject.Project(request.entryPath(),selected.stream().map(s->new PythonCodeProject.File(s.path(),s.content())).toList()));
        var files=request.dataFileIds()==null||request.dataFileIds().isEmpty()?List.<UserAssetService.File>of():data.validate(actor.owner(),request.dataFileIds());
        var skills=request.skillIds()==null||request.skillIds().isEmpty()?List.<com.chatchat.common.skills.DomainSkillRuntimePort.DomainSkillContent>of():assistant.codeReuseSkills(actor.tenant(),actor.user(),request.instruction(),request.skillIds());
        if(request.skillIds()!=null&&!request.skillIds().isEmpty()&&!new HashSet<>(skills.stream().map(com.chatchat.common.skills.DomainSkillRuntimePort.DomainSkillContent::id).toList()).equals(new HashSet<>(request.skillIds())))throw new SecurityException("One or more selected Skills are unavailable or unauthorized");
        var snapshot=new Snapshot(request.requestId(),request.instruction(),request.modelName(),project.entryPath(),List.copyOf(selected),files,skills);
        String encoded=encode(snapshot),hash=PythonCodeProject.hash(encoded);
        if(!checkpoints.compareAndSetExecutionCheckpoint(scope,SNAPSHOT,null,encoded)
            && !encoded.equals(checkpoints.readExecutionCheckpoint(scope,SNAPSHOT).orElseThrow()))throw new ResponseStatusException(CONFLICT,"Request ID is already bound to another source version or objective");
        return Map.of("requestId",request.requestId(),"snapshotSha256",hash,"sources",selected,"dataFiles",files,"executionAuthorized",false,
            "instruction",snapshot.instruction(),"modelName",Objects.toString(snapshot.modelName(),""));
    }
    private Source resolve(Actor actor,Reference ref) {
        return resolve(actor,ref,null);
    }
    private Source resolve(Actor actor,Reference ref,KernelDataScope inputScope) {
        if(ref==null||ref.kind()==null)throw new IllegalArgumentException("Code reference required");
        String path=PythonCodeProject.path(ref.path()),content,id=ref.id();int version=ref.version()==null?0:ref.version();
        switch(ref.kind()) {
            case "SCRIPT" -> {
                var script=owned(actor,id);if(version<1)throw new IllegalArgumentException("Exact script version required");
                var saved=versions.findByScriptIdAndVersionNumber(id,version).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Python source version unavailable"));
                if(!PythonCodeProject.hash(saved.getSourceCode()).equals(saved.getSourceHash()))throw new SecurityException("Stored Python source version digest mismatch");
                if(!path.equals(scriptPath(actor,script)))throw new IllegalArgumentException("Script path differs from the authorized project hierarchy");
                content=saved.getSourceCode();
            }
            case "TEMPLATE" -> {
                var template=templates.findByIdAndTenantId(id,actor.tenant()).filter(t->actor.legacyUser().equals(t.getOwnerId())&&"PUBLISHED".equals(t.getStatus()))
                    .orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Authorized published source template unavailable"));
                if(version!=template.getScriptVersion())throw new ResponseStatusException(CONFLICT,"Template source version changed");
                content=template.getSourceSnapshot();
            }
            case "ASSET" -> {
                if(ref.version()!=null&&ref.version()!=1)throw new IllegalArgumentException("Immutable source assets use version 1 and their content digest");
                var file=assets.get(actor.owner(),id);if(!file.fileName().endsWith(".py")||!Set.of("DOCUMENT","CODE","ARTIFACT").contains(file.kind())||file.size()>PythonCodeProject.MAX_BYTES)throw new IllegalArgumentException("Select a bounded Python source asset");
                content=decodeUtf8(assets.read(actor.owner(),id));version=1;
            }
            case "SNIPPET" -> {
                if(ref.content()==null||ref.content().getBytes(StandardCharsets.UTF_8).length>PythonCodeProject.MAX_BYTES)throw new IllegalArgumentException("Bounded source snippet required");
                if(!PythonCodeProject.hash(ref.content()).equals(ref.sha256()))throw new ResponseStatusException(CONFLICT,"Snippet digest differs from its source");
                if(inputScope==null)throw new SecurityException("Source input scope required");
                content=ref.content();var file=assets.store(actor.owner(),UserAssetService.Kind.CODE,"python-source-input:"+inputScope.requestId(),inputScope.runId(),"python-studio-snippet:"+path,path.substring(path.lastIndexOf('/')+1),"text/x-python",content.getBytes(StandardCharsets.UTF_8));
                id=file.id();version=1;
            }
            case "WORKSPACE" -> {
                var output=assistant.codingResult(actor.tenant(),actor.legacyUser(),id,false);
                var outcome=(CodingTaskOutcome)output.get("outcome");
                if(outcome.status()!=CodingTaskOutcome.Status.COMPLETED||version!=outcome.revision())throw new ResponseStatusException(CONFLICT,"Completed workspace revision required");
                var tree=json.valueToTree(output.get("output"));content=null;
                for(var file:tree.path("files"))if(path.equals(file.path("path").asText()))content=file.path("content").asText(null);
                if(content==null)throw new ResponseStatusException(NOT_FOUND,"Workspace source file unavailable");
            }
            case "ANALYSIS" -> {
                if(id==null||!id.matches("[a-zA-Z0-9_-]{8,64}:cline-[a-f0-9]{64}"))throw new IllegalArgumentException("Historical request and operation identity required");
                String[] parts=id.split(":",2);var priorScope=scope(actor,parts[0]);snapshot(actor,parts[0]);
                var artifact=code.find(priorScope,task(priorScope),parts[1]).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Owned historical analysis source unavailable"));
                if(version<1||json.valueToTree(artifact).path("script").path("scriptVersion").asInt()!=version)throw new ResponseStatusException(CONFLICT,"Historical code version differs");
                content=decodeProject((String)artifact.get("content")).orElseThrow(()->new IllegalArgumentException("Historical project source required"))
                    .files().stream().filter(f->path.equals(f.path())).findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Historical project file unavailable")).content();
                if(inputScope==null||!PythonCodeProject.hash(content).equals(ref.sha256()))throw new ResponseStatusException(CONFLICT,"Historical source digest differs");
                var file=assets.store(actor.owner(),UserAssetService.Kind.CODE,"python-source-input:"+inputScope.requestId(),inputScope.runId(),
                    "python-studio-analysis:"+id+":v"+version+":"+path,path.substring(path.lastIndexOf('/')+1),"text/x-python",content.getBytes(StandardCharsets.UTF_8));
                id=file.id();version=1;
            }
            default -> throw new IllegalArgumentException("Unsupported Python code reference kind");
        }
        if(content==null||!PythonCodeProject.hash(content).equals(ref.sha256()))throw new ResponseStatusException(CONFLICT,"Source content digest differs from the selected version");
        return new Source(Set.of("SNIPPET","ANALYSIS").contains(ref.kind())?"ASSET":ref.kind(),id,version,ref.sha256(),path,content);
    }
    public Map<String,Object> execute(Actor actor,String requestId,Execute consent) {
        var scope=scope(actor,requestId);String encoded=checkpoints.readExecutionCheckpoint(scope,SNAPSHOT).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Prepared source snapshot unavailable"));
        if(consent==null||!consent.authorizeExecution()||!PythonCodeProject.hash(encoded).equals(consent.snapshotSha256()))throw new SecurityException("Explicit execution authorization for this exact source snapshot is required");
        var snapshot=read(encoded);revalidate(actor,snapshot);
        var sources=snapshot.dataFiles().isEmpty()?new LinkedHashMap<String,Dataset>():data.load(actor.owner(),snapshot.dataFiles().stream().map(UserAssetService.File::id).toList());
        if(sources.isEmpty())sources.put("python-studio:no-business-data",new Dataset("python-studio:no-business-data",Map.of("recordMeaning","NO_BUSINESS_DATA_SUPPLIED"),List.of(Map.of("businessDataAvailable",false,"limitation","Only explicitly supplied source code is available. Do not invent business observations."))));
        var model=models.resolveChatModel(snapshot.modelName());var attributes=new LinkedHashMap<String,Object>();
        attributes.put("checkpointModelConfig",models.checkpointModelConfiguration(snapshot.modelName(),model));
        attributes.put("pythonStudioCodeProject",PythonCodeProject.context(project(snapshot),this::encode));
        var metadata=new LinkedHashMap<String,Object>();if(snapshot.modelName()!=null)metadata.put("modelName",snapshot.modelName());
        String objective=snapshot.instruction();
        if(snapshot.skills()!=null&&!snapshot.skills().isEmpty())objective+="\nExplicitly selected authorized methodology (not data evidence or execution permission):\n"+assistant.codeReuseSkillGuidance(snapshot.skills());
        var context=new AnalysisCoverageCoordinator.Request(model,objective,null,attributes,metadata,()->false,()->{},
            GovernanceIsolationScope.runtime(actor.tenant(),actor.user(),scope.runId(),requestId,null),null);
        var receipt=new LinkedHashMap<>(coding.executePrimaryAnalysis(context,sources,event->{}));
        if(Boolean.TRUE.equals(receipt.get("resultAvailable"))&&receipt.get("datasetReference") instanceof String ref)receipt.put("results",sources.get(ref).handle().readPage(0,1).rows());
        String previous=checkpoints.readExecutionCheckpoint(scope,RECEIPT).orElse(null);
        checkpoints.compareAndSetExecutionCheckpoint(scope,RECEIPT,previous,encode(receipt));return receipt;
    }
    private void revalidate(Actor actor,Snapshot snapshot) {
        for(var source:snapshot.sources())resolve(actor,new Reference(source.kind(),source.id(),source.version(),source.sha256(),source.path(),null));
        if(snapshot.skills()!=null&&!snapshot.skills().isEmpty()) {
            var ids=snapshot.skills().stream().map(com.chatchat.common.skills.DomainSkillRuntimePort.DomainSkillContent::id).toList();
            if(!new HashSet<>(snapshot.skills()).equals(new HashSet<>(assistant.codeReuseSkills(actor.tenant(),actor.user(),snapshot.instruction(),ids))))throw new SecurityException("Selected methodology was revoked or changed; prepare a new task");
        }
        if(!snapshot.dataFiles().isEmpty()&&!data.validate(actor.owner(),snapshot.dataFiles().stream().map(UserAssetService.File::id).toList()).equals(snapshot.dataFiles()))throw new ResponseStatusException(CONFLICT,"Authorized data snapshot changed");
    }
    public Map<String,Object> status(Actor actor,String requestId,boolean cancel) {
        var scope=scope(actor,requestId);var snapshot=snapshot(actor,requestId);var result=new LinkedHashMap<String,Object>();
        revalidate(actor,snapshot);
        result.put("requestId",requestId);result.put("sources",snapshot.sources());result.put("dataFiles",snapshot.dataFiles());
        result.put("instruction",snapshot.instruction());result.put("modelName",Objects.toString(snapshot.modelName(),""));
        result.put("snapshotSha256",PythonCodeProject.hash(checkpoints.readExecutionCheckpoint(scope,SNAPSHOT).orElseThrow()));
        result.put("applied",applications(checkpoints.readExecutionCheckpoint(scope,APPLIED).orElse(null)));
        String task=task(scope);result.put("taskId",task);
        if(checkpoints.readExecutionCheckpoint(scope,"coding:analysis-request:"+task).isEmpty()) {
            boolean preparing=checkpoints.readExecutionCheckpoint(scope,"coding:analysis-tool-budget.v2:"+task).isPresent();
            result.put("status",preparing?"PREPARING":"PREPARED");result.put("recoveryRequired",preparing);return result;
        }
        return identity.as(scope,EnumSet.allOf(CodingTaskAccess.Action.class),()->{
            CodingTaskOutcome outcome;
            try{outcome=cancel?agent.cancel(scope,task,"Cancelled by Python Studio owner"):agent.get(scope,task);}
            catch(CodingContractException pending){if(pending.error().code()!=CodingErrorCode.NOT_FOUND)throw pending;result.put("status","PREPARING");result.put("recoveryRequired",true);return result;}
            if(cancel&&!outcome.status().terminal())outcome=agent.resume(scope,task);
            var accepted=tasks.contract(scope,task);
            if(accepted!=null)result.put("budget",Map.of("deadlineAt",accepted.budget().deadlineAt(),"maxModelCalls",accepted.budget().maxModelCalls(),"maxCodeExecutions",accepted.budget().maxCodeExecutions()));
            result.put("recoveryRequired",!outcome.status().terminal()&&!outcome.cancellationRequested()
                &&accepted!=null&&accepted.budget().deadlineAt().isAfter(java.time.Instant.now())&&parentModels!=null&&parentModels.find(scope,task)==null);
            var events=tasks.events(scope,task,Math.max(0,outcome.lastEventSequence()-256),256);
            result.put("outcome",outcome);result.put("status",outcome.status().name());result.put("events",events);
            var executions=tasks.executionFeedback(scope,task);result.put("executions",executions);
            result.put("businessValidation","NOT_EVALUATED");
            var artifacts=new LinkedHashMap<String,Object>();
            var operations=new LinkedHashSet<String>();executions.forEach(e->operations.add(e.executionId()));events.stream().map(CodingExecutionEvent::operationId).filter(Objects::nonNull).forEach(operations::add);
            for(var operation:operations)code.find(scope,task,operation).ifPresent(a->{
                var artifact=new LinkedHashMap<String,Object>(a);var decoded=decodeProject((String)a.get("content"));
                decoded.ifPresent(p->artifact.put("project",p));artifact.remove("content");artifacts.put(operation,artifact);
            });
            result.put("codeVersions",artifacts.values());
            if(outcome.status()==CodingTaskOutcome.Status.COMPLETED&&outcome.validationStatus()!=CodingTaskOutcome.ValidationStatus.FAILED&&outcome.resultDatasetRef()!=null) {
                result.put("results",collector.registeredResult(scope,task,outcome.resultDatasetRef()).handle().readPage(0,1).rows());result.put("resultAvailable",true);
            }else result.put("resultAvailable",false);
            return result;
        });
    }
    @Transactional
    public Map<String,Object> apply(Actor actor,String requestId,Apply request) {
        var snapshot=snapshot(actor,requestId);var scope=scope(actor,requestId);
        revalidate(actor,snapshot);
        if(request==null||request.operationId()==null||!request.operationId().matches("cline-[a-f0-9]{64}")||request.paths()==null||request.paths().isEmpty()
            ||request.paths().size()>32||new HashSet<>(request.paths()).size()!=request.paths().size())throw new IllegalArgumentException("Select reviewed source changes");
        var artifact=code.find(scope,task(scope),request.operationId()).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Code proposal unavailable"));
        if(!Objects.equals(artifact.get("sha256"),request.codeSha256()))throw new ResponseStatusException(CONFLICT,"Reviewed code proposal changed");
        var project=decodeProject((String)artifact.get("content")).orElseThrow(()->new IllegalArgumentException("Project code proposal required"));
        var original=new HashMap<String,Source>();snapshot.sources().forEach(s->original.put(s.path(),s));
        var locked=new HashMap<String,PythonScriptEntity>();
        for(var source:snapshot.sources().stream().filter(s->"SCRIPT".equals(s.kind())).sorted(Comparator.comparing(Source::id)).toList()) {
            var script=scripts.findLockedByIdAndTenantIdAndOwnerId(source.id(),actor.tenant(),actor.legacyUser()).orElseThrow(()->new SecurityException("Source access revoked"));
            if(script.getCurrentVersion()!=source.version()||!PythonCodeProject.hash(script.getSourceCode()).equals(source.sha256())||!scriptPath(actor,script,true).equals(source.path()))throw new ResponseStatusException(CONFLICT,"Source changed after proposal; reload and regenerate before applying");
            locked.put(source.id(),script);
        }
        var byPath=new HashMap<String,PythonCodeProject.File>();project.files().forEach(f->byPath.put(f.path(),f));
        for(String path:request.paths())if(!byPath.containsKey(path))throw new IllegalArgumentException("Selected proposal file unavailable");
        // Existing Studio enforces unique basename per environment; never flatten or overwrite a different file.
        var saved=new ArrayList<PythonScriptEntity>();var written=new ArrayList<Map<String,Object>>();
        for(String path:request.paths()) {
            var source=original.get(path);var file=byPath.get(path);var script=source==null?null:locked.get(source.id());
            if(script!=null) {
                if(!script.getSourceCode().equals(file.content())) {
                    var value=studio.saveScript(actor.tenant(),actor.legacyUser(),new PythonDataScienceService.ScriptRequest(script.getId(),script.getAssetId(),script.getFolderId(),script.getFileName(),script.getTitle(),file.content(),script.getCurrentVersion()));
                    saved.add(value);written.add(appliedFile(path,value));
                }
            }else {
                if(request.targetAssetId()==null||request.targetAssetId().isBlank())throw new IllegalArgumentException("Choose an owned target environment for new source files");
                String name=path.substring(path.lastIndexOf('/')+1);
                if(scripts.findByAssetIdAndFileName(request.targetAssetId(),name).isPresent())throw new ResponseStatusException(CONFLICT,"Existing Studio environment already contains this basename; nothing was overwritten");
                String parent=null;var parts=path.split("/");for(int i=0;i<parts.length-1;i++) {
                    var existing=folders.findByTenantIdAndOwnerIdAndParentIdAndNameIgnoreCase(actor.tenant(),actor.legacyUser(),parent,parts[i]);
                    parent=existing.isPresent()?existing.get().getId():studio.saveFolder(actor.tenant(),actor.legacyUser(),new PythonDataScienceService.FolderRequest(null,parent,parts[i],0)).getId();
                }
                var value=studio.saveScript(actor.tenant(),actor.legacyUser(),new PythonDataScienceService.ScriptRequest(null,request.targetAssetId(),parent,name,name,file.content(),0));
                saved.add(value);written.add(appliedFile(path,value));
            }
        }
        var selected=request.paths().stream().sorted().toList();
        String receiptId=PythonCodeProject.hash(encode(List.of(request.operationId(),request.codeSha256(),selected,Objects.toString(request.targetAssetId(),""))));
        var receipt=Map.<String,Object>of("receiptId",receiptId,"operationId",request.operationId(),"codeSha256",request.codeSha256(),"paths",selected,"saved",written,"confirmedAt",java.time.Instant.now().toString());
        checkpoints.mutateExecutionCheckpoint(scope,APPLIED,previous->{
            var list=new ArrayList<>(applications(previous));
            if(list.stream().anyMatch(item->receiptId.equals(item.get("receiptId"))))return previous;
            if(list.size()>=64)throw new IllegalStateException("Code application history limit reached");
            list.add(receipt);return encode(list);
        });
        return Map.of("saved",saved,"operationId",request.operationId(),"codeSha256",request.codeSha256(),"receipt",receipt);
    }
    private static Map<String,Object> appliedFile(String path,PythonScriptEntity value){return Map.of("path",path,"scriptId",value.getId(),"assetId",value.getAssetId(),"fileName",value.getFileName(),"version",value.getCurrentVersion(),"sha256",PythonCodeProject.hash(value.getSourceCode()));}
    private List<Map<String,Object>> applications(String value){try{return value==null?List.of():json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid persisted code application receipts",invalid);}}
    private PythonScriptEntity owned(Actor actor,String id){return scripts.findByIdAndTenantIdAndOwnerId(id,actor.tenant(),actor.legacyUser()).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Authorized Python script unavailable"));}
    private String scriptPath(Actor actor,PythonScriptEntity script) {
        return scriptPath(actor,script,false);
    }
    private String scriptPath(Actor actor,PythonScriptEntity script,boolean lockFolders) {
        var parts=new ArrayDeque<String>();parts.addFirst(script.getFileName());String folder=script.getFolderId();var seen=new HashSet<String>();
        while(folder!=null&&!folder.isBlank()) {
            if(!seen.add(folder)||seen.size()>16)throw new IllegalArgumentException("Invalid Studio folder hierarchy");
            var saved=(lockFolders?folders.findByIdAndTenantIdAndOwnerId(folder,actor.tenant(),actor.legacyUser()):folders.findFirstByIdAndTenantIdAndOwnerId(folder,actor.tenant(),actor.legacyUser())).orElseThrow(()->new SecurityException("Source folder access unavailable"));parts.addFirst(saved.getName());folder=saved.getParentId();
        }
        return PythonCodeProject.path(String.join("/",parts));
    }
    private Snapshot snapshot(Actor actor,String request){return read(checkpoints.readExecutionCheckpoint(scope(actor,request),SNAPSHOT).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Prepared Python project unavailable")));}
    private Snapshot read(String value){try{return json.readValue(value,Snapshot.class);}catch(java.io.IOException e){throw new IllegalStateException("Invalid persisted code snapshot",e);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(java.io.IOException e){throw new IllegalArgumentException("Invalid code reuse input",e);}}
    private Optional<PythonCodeProject.Project> decodeProject(String source){return PythonCodeProject.decode(source).map(payload->{try{return PythonCodeProject.validate(json.readValue(payload,PythonCodeProject.Project.class));}catch(java.io.IOException e){throw new IllegalArgumentException("Invalid source project",e);}});}
    private static PythonCodeProject.Project project(Snapshot snapshot){return PythonCodeProject.validate(new PythonCodeProject.Project(snapshot.entryPath(),snapshot.sources().stream().map(s->new PythonCodeProject.File(s.path(),s.content())).toList()));}
    private static String decodeUtf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw new IllegalArgumentException("Python source must be UTF-8",e);}}
    static KernelDataScope scope(Actor actor,String request) {
        if(request==null||!request.matches("[a-zA-Z0-9_-]{8,64}"))throw new IllegalArgumentException("Stable Python request ID required");
        String run="python-reuse-"+PythonCodeProject.hash(actor.tenant()+"\0"+actor.user()+"\0"+request);
        return new KernelDataScope(actor.tenant(),actor.user(),request,null,run,null,Map.of());
    }
    private String task(KernelDataScope scope){try{return "analysis-coding-"+PythonCodeProject.hash(json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(Map.of("tenant",scope.tenantId(),"user",scope.userId(),"run",scope.runId(),"request","primary-data-analysis-v1")));}catch(java.io.IOException e){throw new IllegalStateException(e);}}
}
