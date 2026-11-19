package com.chatchat.api.runtime.coding.nativecline;

import com.chatchat.agents.runtime.coding.CodingTaskAccess;
import com.chatchat.api.assets.SiloCodingWorkspaceService;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.evidence.JdbcRuntimeEvidenceStore;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.EvidenceLineage;
import com.chatchat.common.runtime.evidence.EvidenceRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Opt-in immutable input/contract capture for the existing Coding SPI.
 * The durable Coding task remains the task authority; Silo state is its transport
 * checkpoint. This component neither starts a model nor generates/repairs code.
 * Public HTTP and Native dispatch require separate, negotiated transport wiring.
 */
@Service
@ConditionalOnProperty(prefix="chatchat.runtime.coding.unified",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="chatchat.coding",name="enabled",havingValue="true")
public final class NativeSiloTaskService {
    public static final String PROFILE="runtime-coding:native-python-silo:v1";
    public static final String STUDIO_PROFILE="runtime-coding:native-studio:v1";
    public static boolean studio(CodingTaskContract contract){return contract.metadataRefs().contains(STUDIO_PROFILE);}
    private static final String INPUT_TYPE="CODING_NATIVE_SILO_INPUT";
    private static final List<String> TOOLS=List.of("read_files","search_codebase","editor","apply_patch","skills","run_commands");
    private final CodingInvocationContext identity;
    private final SiloCodingWorkspaceService workspace;
    private final JdbcRuntimeEvidenceStore evidence;
    private final ObjectMapper json;

    public NativeSiloTaskService(CodingInvocationContext identity,SiloCodingWorkspaceService workspace,
            JdbcRuntimeEvidenceStore evidence,ObjectMapper json){
        this.identity=identity;this.workspace=workspace;this.evidence=evidence;this.json=json;
    }
    public static boolean supports(CodingTaskContract contract){
        return CodingProtocol.WORKSPACE_VERSION.equals(contract.schemaVersion())
            && contract.outputContract().resultFormat()==CodingTaskContract.ResultFormat.WORKSPACE_FILES
            && contract.metadataRefs().contains(PROFILE);
    }
    private static String contractKey(String task){return "coding:native-silo-contract:"+task;}
    private static String inputId(String task){return "coding:native-silo-input:"+task;}

    /** Caller must already bind the authenticated Runtime actor. Budget and
     * selected model are server choices, never privileges accepted from Cline.
     * Runtime verifies the generic delivery contract, not domain report semantics.
     */
    public CodingTaskContract capture(KernelDataScope scope,String task,List<String> originalFileIds,
            String instruction,String selectedModel,CodingTaskContract.Budget budget){
        return capture(scope,task,originalFileIds,instruction,selectedModel,budget,
            Map.of("profile","artifact-integrity-v1"),List.of("SOURCE","REPORT","RESULT","CHART"));
    }
    public CodingTaskContract capture(KernelDataScope scope,String task,List<String> originalFileIds,
            String instruction,String selectedModel,CodingTaskContract.Budget budget,
            Map<String,Object> verification,List<String> artifactTypes){
        return capture(scope,task,originalFileIds,instruction,selectedModel,budget,verification,artifactTypes,Map.of());
    }
    public CodingTaskContract capture(KernelDataScope scope,String task,List<String> originalFileIds,
            String instruction,String selectedModel,CodingTaskContract.Budget budget,
            Map<String,Object> verification,List<String> artifactTypes,Map<String,Object> resultSchema){
        return capture(scope,task,originalFileIds,instruction,selectedModel,budget,verification,artifactTypes,resultSchema,Map.of());
    }
    public CodingTaskContract capture(KernelDataScope scope,String task,List<String> originalFileIds,String instruction,String selectedModel,CodingTaskContract.Budget budget,Map<String,Object> verification,List<String> artifactTypes,Map<String,Object> resultSchema,Map<String,Object> capabilities){
        return capture(scope,task,originalFileIds,instruction,selectedModel,budget,verification,artifactTypes,resultSchema,capabilities,Map.of());
    }

    CodingTaskContract capture(KernelDataScope scope,String task,List<String> originalFileIds,String instruction,String selectedModel,
            CodingTaskContract.Budget budget,Map<String,Object> verification,List<String> artifactTypes,
            Map<String,Object> resultSchema,Map<String,Object> capabilities,Map<String,Object> studio){
        identity.require(scope,CodingTaskAccess.Action.SUBMIT);
        identity.require(scope,CodingTaskAccess.Action.READ);
        boolean workspaceReview=!studio.isEmpty();
        if(workspaceReview&&(budget==null||budget.maxCodeExecutions()!=0||!artifactTypes.equals(List.of("RESULT"))))
            throw new IllegalArgumentException("Studio only permits bounded source proposals");
        String profile=Objects.toString(verification.get("profile"),"");
        if(!verification.equals(Map.of("profile","artifact-integrity-v1")))throw new IllegalArgumentException("Only generic artifact integrity verification is supported");
        if(artifactTypes==null||artifactTypes.isEmpty()||!Set.of("SOURCE","REPORT","RESULT","CHART","TEST","AUXILIARY").containsAll(artifactTypes))throw new IllegalArgumentException("Known delivery types required");
        if(task==null||!task.matches("[A-Za-z0-9_-]{1,96}")||instruction==null||instruction.isBlank()
                ||instruction.length()>32768||selectedModel==null||!selectedModel.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Explicit task, original objective and frozen model required");
        if(budget==null||!workspaceReview&&(budget.maxModelCalls()!=-1||budget.maxToolCalls()!=-1||budget.maxCodeExecutions()!=-1))
            throw new IllegalArgumentException("Native profile requires unlimited model/tool/code execution counts");
        if(!workspaceReview&&(budget.maxMemoryBytes()<1||budget.maxOutputBytes()<1))
            throw new IllegalArgumentException("Native profile requires explicit memory and output resource protection");
        List<String> files=List.copyOf(originalFileIds);
        var saved=evidence.readExecutionCheckpoint(scope,contractKey(task));
        if(saved.isPresent()){
            var contract=decode(saved.get());var input=requireAuthorized(contract);
            if(!files.equals(input.get("originalFileIds"))||!instruction.equals(contract.objective().instruction())
                    ||!selectedModel.equals(input.get("selectedModel"))
                    ||input.containsKey("verification")&&!verification.equals(input.get("verification"))
                    ||!Objects.equals(com.chatchat.common.runtime.coding.CodingReasoning.submittedFromContext(contract.runtimeContext()),
                        com.chatchat.common.runtime.coding.CodingReasoning.submittedFromContext(capabilities))
                    ||!studio.equals(input.getOrDefault("studio",Map.of()))
                    ||!artifactTypes.equals(input.get("artifactTypes"))||!resultSchema.equals(input.getOrDefault("resultSchema",Map.of())))
                throw new IllegalArgumentException("Immutable Native task request conflict");
            return contract; // Never extend its deadline or replace a failed task.
        }
        // Existing owner checks happen inside Silo create before reading any
        // object. Original bytes/names/types are retained, not converted to rows.
        var state=workspace.create(scope,task,files);
        String decision="native-silo-owner-inputs:"+task,work="native-silo-workspace:"+task;
        var input=new LinkedHashMap<String,Object>();
        input.put("profile",PROFILE);input.put("taskId",task);input.put("runId",scope.runId());
        input.put("tenantId",scope.tenantId());input.put("userId",scope.userId());
        input.put("originalFileIds",files);input.put("inputs",state.inputs());
        input.put("snapshotVersion",state.snapshotVersion());input.put("transportEpoch",state.epoch());
        input.put("selectedModel",selectedModel);input.put("verification",Map.copyOf(verification));input.put("artifactTypes",List.copyOf(artifactTypes));
        input.put("resultSchema",Map.copyOf(resultSchema));
        if(workspaceReview)input.put("studio",Map.copyOf(studio));
        input.put("decisionRef",decision);input.put("workspaceRef",work);
        input.put("objectiveSha256",com.chatchat.api.assets.UserAssetService.hash(instruction.getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> payload=json.convertValue(input,new TypeReference<>(){});
        evidence.register(new EvidenceRecord(null,inputId(task),scope,INPUT_TYPE,"NativeSiloTaskService",
                evidence.contentHash(payload),payload,null,System.currentTimeMillis(),Map.of()),
            new EvidenceLineage(null,inputId(task),scope,List.of(),task,"NativeSiloTaskService",null,Map.of()));
        var runtime=new LinkedHashMap<String,Object>(capabilities);runtime.put("schemaVersion","runtime_os.coding.context.v1");runtime.put("consistency","TASK_SNAPSHOT");var effectiveModel=new LinkedHashMap<String,Object>();if(runtime.get("model") instanceof Map<?,?> captured)captured.forEach((k,v)->effectiveModel.put(k.toString(),v));effectiveModel.put("selectedModel",selectedModel);runtime.put("model",effectiveModel);CodingRuntimeContext.validate(runtime);
        var tools=new ArrayList<String>(TOOLS);
        var profiles=new ArrayList<>(List.of(PROFILE));
        if(workspaceReview){
            profiles.add(STUDIO_PROFILE);
            tools.remove("run_commands");
            if(studio.get("schema") instanceof Map<?,?> schema && schema.containsKey("discoveryId")){
                profiles.add(com.chatchat.api.datascience.sql.SqlWorkspaceMetadataTools.PROFILE);
                tools.addAll(List.of("workspace_metadata","workspace_validate","runtime__workspace_metadata","runtime__workspace_validate",
                    "tenant_data__workspace_metadata","tenant_data__workspace_validate"));
            }
        }
        if(runtime.get("skills") instanceof Map<?,?> catalog && catalog.get("skills") instanceof List<?> cards
                && cards.stream().anyMatch(card->card instanceof Map<?,?> m&&"ON_DEMAND".equals(m.get("version")))) {
            tools.add("agent_skill_read");tools.add("runtime__agent_skill_read");
        }
        if(runtime.containsKey("internetSearch")){tools.addAll(com.chatchat.api.runtime.coding.knowledge.CodingInternetAccess.NATIVE_TOOLS);tools.add("runtime__*");}
        if(runtime.containsKey("documentSearch")){tools.add("document_search");tools.add("runtime__document_search");}
        var contract=new CodingTaskContract(CodingProtocol.WORKSPACE_VERSION,task,scope,
            new CodingTaskContract.Objective(instruction,artifactTypes,List.of(profile)),
            "runtime-evidence:"+inputId(task),List.copyOf(profiles),work,
            new CodingTaskContract.Authorization(decision,tools,state.inputs().stream().map(SiloCodingWorkspaceService.Input::assetId).toList(),
                List.of("native-task-input"),List.of("native-task-workspace"),CodingTaskContract.NetworkPolicy.NONE,List.of(),
                workspaceReview?List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE):List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE,CodingTaskContract.SideEffect.CODE_EXECUTION)),
            budget,"native-runtime:cline4.1.26-python3.11",profile,
            new CodingTaskContract.OutputContract(CodingTaskContract.ResultFormat.WORKSPACE_FILES,"native-delivery-result.v1","native-delivery-validation.v1",
                List.of("delivery_manifest.json"),List.of("application/json","text/markdown","image/png","text/x-python"),
                workspaceReview?1:CodingProtocol.MAX_SAFE_INTEGER,workspaceReview?512*1024:budget.maxOutputBytes(),
                List.of(CodingTaskContract.RequiredEvidence.values())),
            runtime);
        if(!evidence.compareAndSetExecutionCheckpoint(scope,contractKey(task),null,encode(contract)))
            contract=decode(evidence.readExecutionCheckpoint(scope,contractKey(task)).orElseThrow());
        requireAuthorized(contract);
        return contract;
    }

    /** Rechecks the immutable task contract and owner-scoped snapshot. No caller
     * supplied task path, provider key, S3 URL or verifier can grant authority.
     */
    public Map<String,Object> requireAuthorized(CodingTaskContract contract){
        identity.require(contract.scope(),CodingTaskAccess.Action.READ);
        if(!supports(contract))throw new SecurityException("Native Silo profile required");
        var saved=decode(evidence.readExecutionCheckpoint(contract.scope(),contractKey(contract.taskId())).orElseThrow());
        if(!CodingCanonicalHash.sha256(contract).equals(CodingCanonicalHash.sha256(saved)))
            throw new SecurityException("Native contract differs from its authorized immutable snapshot");
        if(!contract.inputSnapshotRef().equals("runtime-evidence:"+inputId(contract.taskId())))
            throw new SecurityException("Native input reference does not belong to task");
        var record=evidence.find(contract.scope(),inputId(contract.taskId())).orElseThrow();
        var lineage=evidence.lineage(contract.scope(),inputId(contract.taskId())).orElseThrow();
        if(!INPUT_TYPE.equals(record.evidenceType())||!contract.taskId().equals(lineage.producingTaskId())||!evidence.contentHash(record.payload()).equals(record.contentSha256()))
            throw new SecurityException("Native input provenance differs");
        var state=workspace.get(contract.scope(),contract.taskId());var input=record.payload();
        if(!Map.of("profile","artifact-integrity-v1").equals(input.get("verification")))
            throw new SecurityException("Stored task requires an unsupported verification contract");
        if(studio(contract)!=input.containsKey("studio")||studio(contract)&&(contract.budget().maxCodeExecutions()!=0
                ||contract.authorization().allowedTools().contains("run_commands")
                ||!contract.authorization().allowedSideEffects().equals(List.of(CodingTaskContract.SideEffect.WORKSPACE_WRITE))))
            throw new SecurityException("Studio source-only capability differs");
        if(!state.snapshotVersion().equals(input.get("snapshotVersion"))||!state.epoch().equals(input.get("transportEpoch"))
                ||!contract.authorization().allowedDataRefs().equals(state.inputs().stream().map(SiloCodingWorkspaceService.Input::assetId).sorted().toList()))
            throw new SecurityException("Native input versions or scope changed");
        return input;
    }
    /** Trusted Node callback only; signed capabilities must never become a
     * public user response, model context or a file inside the Agent workspace.
     */
    public CodingTaskContract prepared(com.chatchat.common.kernel.KernelDataScope scope,String task){
        identity.require(scope,CodingTaskAccess.Action.READ);
        var contract=decode(evidence.readExecutionCheckpoint(scope,contractKey(task)).orElseThrow());
        requireAuthorized(contract);return contract;
    }
    /** Durable request-to-execution identity, including across scheduler
     * rollback. Existing local requests never become a second Temporal task. */
    public String taskIdentity(com.chatchat.common.kernel.KernelDataScope scope,String legacy,String preferred){
        identity.require(scope,CodingTaskAccess.Action.READ);
        String key="native-dispatch-identity:"+legacy;
        var saved=evidence.readExecutionCheckpoint(scope,key);if(saved.isPresent())return saved.get();
        String selected=evidence.readExecutionCheckpoint(scope,contractKey(legacy)).isPresent()?legacy:preferred;
        evidence.compareAndSetExecutionCheckpoint(scope,key,null,selected);
        return evidence.readExecutionCheckpoint(scope,key).orElseThrow();
    }
    public List<SiloCodingWorkspaceService.InputCapability> inputCapabilities(CodingTaskContract contract){
        requireAuthorized(contract);var state=workspace.get(contract.scope(),contract.taskId());
        return workspace.inputCapabilities(contract.scope(),contract.taskId(),state.epoch());
    }
    private String encode(CodingTaskContract contract){try{return json.writeValueAsString(contract);}catch(Exception e){throw new IllegalStateException("Native contract encoding failed",e);}}
    private CodingTaskContract decode(String text){try{return json.readValue(text,CodingTaskContract.class);}catch(Exception e){throw new IllegalStateException("Native contract decoding failed",e);}}
}
