package com.chatchat.api.runtime.coding.nativecode;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.config.ModelResourceRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** General task admission and evidence. No finance/SQL service or repair decisions. */
@Service
@ConditionalOnProperty(prefix="chatchat.coding.native",name="enabled",havingValue="true")
public final class NativeCodingTaskService {
    public record SkillBinding(String id,String version,String agentId){}
    public record Request(String instruction,Map<String,String> files,List<String> readOnlyPaths,String modelName,String editStrategy,String acceptanceCommand,List<SkillBinding> skills,List<NativeCodingMcpService.Binding> mcpServers){
        public Request(String instruction,Map<String,String> files,List<String> readOnlyPaths,String modelName,String editStrategy,String acceptanceCommand,List<SkillBinding> skills){this(instruction,files,readOnlyPaths,modelName,editStrategy,acceptanceCommand,skills,List.of());}
        public Request(String instruction,Map<String,String> files,List<String> readOnlyPaths,String modelName,String editStrategy,String acceptanceCommand){this(instruction,files,readOnlyPaths,modelName,editStrategy,acceptanceCommand,List.of());}
    }
    private com.chatchat.runtime.skill.port.inbound.SkillResolver skillResolver;
    private com.chatchat.common.retrieval.SkillExecutionScopePort skillScopes;
    private boolean admissionEnabled;
    private NativeCodingMcpService mcp;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setMcp(NativeCodingMcpService value){mcp=value;}
    private boolean pipefailVerificationEnabled;
    @Value("${chatchat.coding.native.pipefail-verification-enabled:false}")
    public void setPipefailVerificationEnabled(boolean enabled){pipefailVerificationEnabled=enabled;}
    @Value("${chatchat.coding.native.admission-enabled:false}")
    public void setAdmissionEnabled(boolean enabled){admissionEnabled=enabled;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setSkills(com.chatchat.runtime.skill.port.inbound.SkillResolver resolver,com.chatchat.common.retrieval.SkillExecutionScopePort scopes){skillResolver=resolver;skillScopes=scopes;}
    private final DurableCodingTaskService tasks;private final NativeCodingWorkspaceService workspace;
    private final JdbcRuntimeEvidenceStore evidence;private final ObjectProvider<CodingAgentSPI> agents;
    private final McpPythonControlPlanePort python;private final ModelResourceRegistry models;private final ObjectMapper json;private final String environment;
    public NativeCodingTaskService(DurableCodingTaskService tasks,NativeCodingWorkspaceService workspace,JdbcRuntimeEvidenceStore evidence,
            ObjectProvider<CodingAgentSPI> agents,McpPythonControlPlanePort python,ModelResourceRegistry models,ObjectMapper json,
            @Value("${chatchat.coding.native.environment-id:}")String environment){
        this.tasks=tasks;this.workspace=workspace;this.evidence=evidence;this.agents=agents;this.python=python;this.models=models;this.json=json;this.environment=environment;
    }
    public CodingTaskOutcome submit(KernelDataScope scope,String task,Request request){
        if(!task.equals(scope.runId())||!task.matches("native-[0-9a-f]{48}"))throw new SecurityException("Native task scope required");
        var objective=new CodingTaskContract.Objective(request.instruction(),List.of("source-project.json"),List.of("native-project-tests:v1"));
        var files=NativeCodeWorkspace.files(request.files()==null?Map.of():request.files());
        var readOnly=request.readOnlyPaths()==null?List.<String>of():List.copyOf(request.readOnlyPaths());
        if(readOnly.size()!=new HashSet<>(readOnly).size()||readOnly.stream().anyMatch(p->!files.containsKey(NativeCodeWorkspace.path(p))))throw new IllegalArgumentException("Read-only files must exist in the initial project");
        String strategy=request.editStrategy()==null?"auto":request.editStrategy();
        if(!Set.of("auto","editor","patch").contains(strategy))throw new IllegalArgumentException("Invalid native edit strategy");
        String key="coding:native-contract:"+task;var saved=evidence.readExecutionCheckpoint(scope,key);
        if(saved.isEmpty()&&!admissionEnabled)throw new SecurityException("New native Coding task admission is disabled");
        String model=request.modelName();
        if(model==null||model.isBlank())model=saved.isPresent()?Objects.toString(evidence.find(scope,"native-input:"+task).orElseThrow().payload().get("modelName")):models.defaultChatModel();
        models.require(model);
        String acceptance=request.acceptanceCommand();
        if(acceptance==null||acceptance.isBlank()||acceptance.length()>16384)throw new IllegalArgumentException("Declare the project verification command");
        CodingTaskContract contract;
        var bindings=request.skills()==null?List.<SkillBinding>of():List.copyOf(request.skills());
        var mcpBindings=request.mcpServers()==null?List.<NativeCodingMcpService.Binding>of():List.copyOf(request.mcpServers());
        if(bindings.size()>8||bindings.stream().map(SkillBinding::id).distinct().count()!=bindings.size())throw new IllegalArgumentException("Invalid Skill selection");
        if(saved.isPresent()){
            contract=decode(saved.get(),CodingTaskContract.class);var initial=evidence.find(scope,"native-input:"+task).orElseThrow().payload();
            if(!contract.objective().instruction().equals(request.instruction())||!Objects.equals(initial.get("modelName"),model)
                    ||!Objects.equals(initial.get("editStrategy"),strategy)||!Objects.equals(initial.get("readOnlyPaths"),readOnly)
                    ||!Objects.equals(initial.get("acceptanceCommand"),acceptance)
                    ||!json.valueToTree(initial.getOrDefault("mcpBindings",List.of())).equals(json.valueToTree(mcpBindings))
                    ||!json.valueToTree(initial.getOrDefault("skillBindings",List.of())).equals(json.valueToTree(bindings)))throw new IllegalArgumentException("Native Skill request identity conflict");
            if(!Objects.equals(initial.get("initialFilesHash"),CodingCanonicalHash.sha256Value(files)))throw new IllegalArgumentException("Native source request identity conflict");
        }else{
            var executorPolicy=pipefailVerificationEnabled?NativeExecutorPolicy.PIPEFAIL:NativeExecutorPolicy.LITERAL;
            if(executorPolicy==NativeExecutorPolicy.PIPEFAIL){
                var capabilities=python.inputCapabilities();
                if(capabilities==null||!capabilities.contains(executorPolicy.capability()))throw new SecurityException("MCP has not negotiated the pinned native executor policy");
            }
            var authorizedSkills=bindings.isEmpty()?Map.<String,Object>of():skillCatalog(scope,bindings);
            if(!mcpBindings.isEmpty()&&mcp==null)throw new SecurityException("MCP governance unavailable");
            var authorizedMcp=mcpBindings.isEmpty()?List.<Map<String,Object>>of():mcp.catalog(scope,mcpBindings);
            if(environment.isBlank())throw new SecurityException("Native Sandbox environment is not configured");
            var env=python.environment(environment);var runtime=python.environmentRuntime(environment);
            if(env==null||!"PUBLISHED".equals(env.status())||!"NONE".equals(env.networkPolicy())||runtime.imageDigest()==null)
                throw new SecurityException("An immutable published no-network native environment is required");
            requireEnvironmentEnvelope(env);
            if(runtime.environmentVersion()!=env.versionNumber()||!environment.equals(runtime.environmentId()))throw new SecurityException("Native environment changed during admission");
            var initial=workspace.seed(scope,task,files);var payload=new LinkedHashMap<String,Object>();
            payload.put("taskId",task);payload.put("initial",initial);payload.put("initialFilesHash",CodingCanonicalHash.sha256Value(files));payload.put("modelName",model);
            payload.put("editStrategy",strategy);payload.put("readOnlyPaths",readOnly);payload.put("acceptanceCommand",acceptance);
            payload.put("skillBindings",bindings);
            if(!mcpBindings.isEmpty())payload.put("mcpBindings",mcpBindings);
            if(executorPolicy==NativeExecutorPolicy.PIPEFAIL){payload.put("nativeExecutionPolicy",executorPolicy.id());payload.put("nativeExecutorCapability",executorPolicy.capability());}
            String providerModel=models.connection(model).getModelName();payload.put("routingModelId",providerModel==null||providerModel.isBlank()?model:providerModel);
            String inputId="native-input:"+task;register(scope,task,inputId,NativeCodingWorkspaceService.INPUT_TYPE,payload,List.of());
            var budget=new CodingTaskContract.Budget("native-budget:"+task,Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                8,262144,16,4,"1","USD",300000,536870912,1048576);
            var context=new LinkedHashMap<String,Object>(Map.<String,Object>of("model",Map.of("selectedModel",model,"maxTokens",8192,
                "connectionSha256",NativeModelConfiguration.fingerprint(models,model)),"builtinSkillPolicy",Map.of("allowedIds",List.of()),
                "pythonEnvironment",Map.of("environmentId",environment,"environmentVersion",runtime.environmentVersion(),"dockerImage",runtime.dockerImage(),
                    "imageDigest",runtime.imageDigest(),"pythonVersion",Objects.toString(runtime.pythonVersion(),""),"packages",runtime.packages())));
            context.put("schemaVersion","runtime_os.coding.context.v1");context.put("consistency","TASK_SNAPSHOT");
            if(executorPolicy==NativeExecutorPolicy.PIPEFAIL){context.put("nativeExecutionPolicy",executorPolicy.id());context.put("nativeExecutorCapability",executorPolicy.capability());}
            var tools=new ArrayList<>(List.of("read_files","search_codebase","editor","apply_patch","run_commands"));
            if(!authorizedMcp.isEmpty()){
                context.put(NativeCodingMcpService.CONTEXT,authorizedMcp);
                authorizedMcp.forEach(card->tools.add((String)card.get("agentToolName")));
            }
            if(!bindings.isEmpty()){
                context.put("skills",authorizedSkills);tools.add("skills");
            }
            contract=new CodingTaskContract(CodingProtocol.WORKSPACE_VERSION,task,scope,
                objective,
                "runtime-evidence:"+inputId,List.of(executorPolicy.profile()),"native-workspace:"+task,
                new CodingTaskContract.Authorization("native-authority:"+task,tools,
                    List.of(),List.of("native-project"),List.of("native-project"),CodingTaskContract.NetworkPolicy.NONE,List.of(),
                    List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE,CodingTaskContract.SideEffect.CODE_EXECUTION)),budget,environment,"native-project-tests:v1",
                new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.WORKSPACE_FILES,"native-project.output.v1","native-project.validation.v1",
                    List.of("source-project.json"),List.of("application/json"),32,256000,List.of(CodingTaskContract.RequiredEvidence.values())),context);
            if(!evidence.compareAndSetExecutionCheckpoint(scope,key,null,encode(contract)))
                contract=decode(evidence.readExecutionCheckpoint(scope,key).orElseThrow(),CodingTaskContract.class);
        }
        return agents.getObject().submit(contract);
    }
    private Map<String,Object> skillCatalog(KernelDataScope scope,List<SkillBinding> bindings){
        if(skillResolver==null||skillScopes==null)throw new SecurityException("Published Skill authorization unavailable");
        var cards=new ArrayList<Map<String,Object>>();
        for(var binding:bindings){
            if(binding.id()==null||binding.agentId()==null||binding.version()==null||binding.version().isBlank())throw new SecurityException("Pinned Skill identity required");
            var allowed=skillScopes.resolve(scope.tenantId(),scope.userId(),binding.agentId(),List.of(),List.of());
            if(!allowed.skillAllowed())throw new SecurityException("Skill Agent scope denied");
            var role=new com.chatchat.runtime.skill.api.identity.SkillRoleContext(scope.tenantId(),scope.userId(),allowed.roles(),List.of(),Map.of("agentId",binding.agentId(),"skillPurpose","AGENT_NATIVE"));
            var resolution=skillResolver.resolve(new com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest(binding.id(),binding.version(),role));
            if(!resolution.resolved()||!binding.id().equals(resolution.skill().descriptor().id())||!binding.version().equals(resolution.skill().descriptor().version()))throw new SecurityException("Published Skill version unavailable");
            var descriptor=resolution.skill().descriptor();var card=new LinkedHashMap<String,Object>();
            card.put("id",binding.id());card.put("agentId",binding.agentId());card.put("version",binding.version());card.put("name",descriptor.name());
            card.put("description",descriptor.description());card.put("contentSha256",com.chatchat.agents.protocol.ModelProtocolJson.sha256Hex(resolution.skill().instructions()));cards.add(card);
        }
        return com.chatchat.agents.runtime.context.SkillAnalysisContext.catalog(com.chatchat.agents.runtime.context.SkillAnalysisContext.create("AVAILABLE",cards,Map.of()));
    }
    public CodingTaskOutcome complete(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> outcome){
        synchronized(workspace.ioLock(c)){return completeLocked(c,lease,outcome);}
    }
    private CodingTaskOutcome completeLocked(CodingTaskContract c,CodingJournal.Lease lease,Map<String,Object> outcome){
        workspace.requireAuthorized(c);
        var current=tasks.get(c.scope(),c.taskId());
        if(current.status().terminal())return current;
        tasks.requireWorkerLease(c.scope(),c.taskId(),lease,true);
        if(current.executionControl().stopReason()!=null)return tasks.finishStoppedAfterReconciliation(c.scope(),c.taskId(),lease,current.revision());
        if(!tasks.unresolvedOperations(c.scope(),c.taskId()).isEmpty())throw new IllegalStateException("Native completion requires stopped model/tools/commands");
        var savedIntent=tasks.privateState(c.scope(),c.taskId(),"native-final-intent");
        if(savedIntent!=null)outcome=json.convertValue(savedIntent,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        boolean completed="completed".equals(outcome.get("status"));String base="native-final:"+c.taskId();
        var tree=workspace.current(c);var manifest=(Map<?,?>)tree.get("manifest");
        var execution=tasks.privateState(c.scope(),c.taskId(),"native-last-execution");
        if(completed){
            var verification=tasks.privateState(c.scope(),c.taskId(),"native-last-verification");
            if(verification!=null)execution=verification;
            var requested=(Map<?,?>)outcome.get("workspaceManifest");String operation=Objects.toString(outcome.getOrDefault("verificationExecutionOperationId",outcome.get("lastExecutionOperationId")),"");
            if(requested==null||!manifest.get("sha256").equals(requested.get("sha256"))||execution==null
                    ||!manifest.get("sha256").equals(execution.path("manifestHash").asText())||execution.path("exitCode").asInt(-1)!=0
                    ||!execution.path("executorStopped").asBoolean()||!execution.path("resultAvailable").asBoolean()
                    ||!operation.equals(execution.path("operationId").asText()))throw new SecurityException("Current code has no matching verified execution");
            var prepared=tasks.privateState(c.scope(),c.taskId(),"native-command-input:"+operation);
            String acceptance=Objects.toString(workspace.requireAuthorized(c).get("acceptanceCommand"),"");
            var proof=tasks.verifiedOperation(c.scope(),c.taskId(),operation);
            if(prepared==null||proof.isEmpty()||!proof.get().stopProofRef().equals(execution.path("stopProofRef").asText())
                    ||!manifest.get("sha256").equals(prepared.path("manifestHash").asText())
                    ||!prepared.path("sourceHash").asText().equals(execution.path("sourceHash").asText())
                    ||!prepared.path("commandHash").asText().equals(execution.path("commandHash").asText())
                    ||!NativeCommandVerification.certifies(c,acceptance,prepared,execution))
                throw new SecurityException("Declared verification command was not the completed execution");
            if(((Map<?,?>)tree.get("files")).isEmpty())throw new SecurityException("No code delivered");
        }
        if(savedIntent==null){
            var intent=new LinkedHashMap<String,Object>();intent.put("status",outcome.getOrDefault("status","failed"));
            if(completed){intent.put("workspaceManifest",outcome.get("workspaceManifest"));intent.put("lastExecutionOperationId",outcome.get("lastExecutionOperationId"));
                if(outcome.containsKey("verificationExecutionOperationId"))intent.put("verificationExecutionOperationId",outcome.get("verificationExecutionOperationId"));}
            tasks.privateState(c.scope(),c.taskId(),lease,"native-final-intent",null,json.valueToTree(intent));
        }
        String code=base+":code",receipt=base+":receipt",result=base+":result",validation=base+":validation";
        var records=new ArrayList<EvidenceRecord>();var lineages=new ArrayList<EvidenceLineage>();
        add(records,lineages,c,code,"CODING_NATIVE_CODE",Map.of("manifest",manifest,"source","native-code:"+manifest.get("sha256")),List.of(id(c.inputSnapshotRef())));
        add(records,lineages,c,receipt,"CODING_NATIVE_EXECUTION",execution==null?Map.of("notExecuted",true):json.convertValue(execution,Map.class),List.of(code));
        add(records,lineages,c,result,"CODING_NATIVE_FILE_INDEX",tree,List.of(code,receipt));
        add(records,lineages,c,validation,"CODING_NATIVE_VALIDATION",Map.of("declaredCommandPassed",completed,"independentProjectAcceptance","NOT_EVALUATED"),List.of(result));
        String key="coding:native-final:"+c.taskId(),verified="VERIFIED",committed="COMMITTED";
        if(evidence.readExecutionCheckpoint(c.scope(),key).isEmpty())evidence.compareAndSetExecutionCheckpoint(c.scope(),key,null,verified);
        evidence.commitCodingEvidence(c.scope(),key,verified,committed,records,lineages,base+":bundle",
            List.of(id(c.inputSnapshotRef()),code,receipt,result,validation),Map.of("taskId",c.taskId(),"nativeSdk",true,"independentProjectAcceptance","NOT_EVALUATED"));
        return CodingTaskRevisions.mutate(tasks,c.scope(),c.taskId(),revision->commitFinal(c,lease,completed,base,code,receipt,result,validation,revision));
    }
    private CodingTaskOutcome commitFinal(CodingTaskContract c,CodingJournal.Lease lease,boolean completed,String base,String code,String receipt,String result,String validation,long revision){
        var current=tasks.get(c.scope(),c.taskId());
        if(current.status().terminal())return current;
        if(current.executionControl().stopReason()!=null)return tasks.finishStoppedAfterReconciliation(c.scope(),c.taskId(),lease,revision);
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);if(at.isBefore(current.updatedAt()))at=current.updatedAt();
        var next=new CodingTaskOutcome(current.schemaVersion(),c.taskId(),c.scope(),current.contractSha256(),
            completed?CodingTaskOutcome.Status.COMPLETED:CodingTaskOutcome.Status.FAILED,current.revision()+1,current.lastEventSequence()+1,
            current.createdAt(),at,current.startedAt(),at,false,completed?null:CodingErrorCode.EXECUTION_FAILED,
            completed?"Native project and declared command archived; independent acceptance not evaluated":"Native coding ended without completing the declared command",
            List.of("A successful declared command does not certify independent project acceptance"),c.inputSnapshotRef(),"runtime-evidence:"+code,"runtime-evidence:"+receipt,
            "runtime-evidence:"+result,"runtime-evidence:"+validation,"runtime-evidence:"+base+":bundle",CodingTaskOutcome.ValidationStatus.INCONCLUSIVE,
            current.reproducibility(),current.usage(),new CodingTaskOutcome.ExecutionControl(null,null,true,false),current.parentTaskId(),current.supersedesTaskId(),Map.of());
        var saved=tasks.commitOutcome(c.scope(),c.taskId(),lease,revision,next);
        if(!saved.status().terminal()&&saved.executionControl().stopReason()!=null)
            return tasks.finishStoppedAfterReconciliation(c.scope(),c.taskId(),lease,saved.revision());
        return saved;
    }
    private void register(KernelDataScope scope,String task,String id,String type,Map<String,Object> payload,List<String> parents){
        evidence.register(new EvidenceRecord(null,id,scope,type,"NativeCodingTaskService",evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,id,scope,parents,task,"NativeCodingTaskService",null,Map.of()));
    }
    private void add(List<EvidenceRecord> records,List<EvidenceLineage> lineages,CodingTaskContract c,String id,String type,Map<String,Object> payload,List<String> parents){
        records.add(new EvidenceRecord(null,id,c.scope(),type,"NativeCodingTaskService",evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()));
        lineages.add(new EvidenceLineage(null,id,c.scope(),parents,c.taskId(),"NativeCodingTaskService",null,Map.of()));
    }
    private static String id(String ref){return ref.substring("runtime-evidence:".length());}
    private static void requireEnvironmentEnvelope(McpPythonControlPlanePort.EnvironmentView env){
        try{
            var cpu=new java.math.BigDecimal(env.cpuLimit());String memory=env.memoryLimit().toLowerCase(Locale.ROOT);
            if(!memory.matches("[1-9][0-9]*[kmg]?"))throw new IllegalArgumentException();
            int shift=memory.endsWith("k")?10:memory.endsWith("m")?20:memory.endsWith("g")?30:0;
            var bytes=new java.math.BigInteger(shift==0?memory:memory.substring(0,memory.length()-1)).shiftLeft(shift);
            if(cpu.signum()<=0||cpu.compareTo(java.math.BigDecimal.ONE)>0||bytes.compareTo(java.math.BigInteger.valueOf(536870912))>0
                    ||env.runtimeUser()==null||!env.runtimeUser().matches("[1-9][0-9]{0,9}(:[0-9]{1,10})?"))throw new IllegalArgumentException();
        }catch(Exception invalid){throw new SecurityException("Published native environment exceeds the fixed CPU/memory/user envelope");}
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalArgumentException(error);}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception error){throw new SecurityException("Invalid native contract",error);}}
}
