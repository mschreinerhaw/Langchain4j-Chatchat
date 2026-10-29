package com.chatchat.api.datascience.python;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.api.assets.*;
import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.chatchat.api.runtime.coding.analysis.CodingAnalysisService;
import com.chatchat.api.runtime.coding.cline.CodingCodeArtifactStore;
import com.chatchat.api.runtime.coding.evidence.CodingEvidenceCollector;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.common.runtime.evidence.RuntimeExecutionCheckpointPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PythonCodeReuseServiceTest {
    final PythonScriptRepository scripts=mock(PythonScriptRepository.class);
    final PythonScriptVersionRepository versions=mock(PythonScriptVersionRepository.class);
    final PythonTemplateRepository templates=mock(PythonTemplateRepository.class);
    final PythonScriptFolderRepository folders=mock(PythonScriptFolderRepository.class);
    final PythonDataScienceService studio=mock(PythonDataScienceService.class);
    final UserAssetService assets=mock(UserAssetService.class);
    final AssetAnalysisInputs inputs=mock(AssetAnalysisInputs.class);
    final RuntimeExecutionCheckpointPort checkpoints=mock(RuntimeExecutionCheckpointPort.class);
    final CodingAnalysisService coding=mock(CodingAnalysisService.class);
    final AgentChatModelResolver models=mock(AgentChatModelResolver.class);
    final CodingCodeArtifactStore artifacts=mock(CodingCodeArtifactStore.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final PythonCodeReuseService.Actor actor=new PythonCodeReuseService.Actor("tenant","user-uuid","alice");
    final PythonScriptEntity script=new PythonScriptEntity();
    final Map<String,String> saved=new HashMap<>();
    PythonCodeReuseService service;
    final String content="def total(): return 7\r\n";
    @BeforeEach void setup(){
        service=new PythonCodeReuseService(scripts,versions,templates,folders,studio,mock(PythonCodeAssistantService.class),assets,inputs,checkpoints,coding,models,new CodingInvocationContext(),mock(DurableCodingTaskService.class),mock(CodingAgentSPI.class),artifacts,mock(CodingEvidenceCollector.class),json);
        script.setId("script");script.setFileName("main.py");script.setCurrentVersion(1);script.setSourceCode(content);script.setAssetId("env");
        when(scripts.findByIdAndTenantIdAndOwnerId("script","tenant","alice")).thenReturn(Optional.of(script));
        when(scripts.findLockedByIdAndTenantIdAndOwnerId("script","tenant","alice")).thenReturn(Optional.of(script));
        var version=new PythonScriptVersionEntity();version.setSourceCode(content);version.setVersionNumber(1);version.setSourceHash(PythonCodeProject.hash(content));
        when(versions.findByScriptIdAndVersionNumber("script",1)).thenReturn(Optional.of(version));
        when(checkpoints.readExecutionCheckpoint(any(),anyString())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0).toString()+i.getArgument(1))));
        when(checkpoints.compareAndSetExecutionCheckpoint(any(),anyString(),nullable(String.class),anyString())).thenAnswer(i->{String key=i.getArgument(0).toString()+i.getArgument(1);if(!Objects.equals(saved.get(key),i.getArgument(2)))return false;saved.put(key,i.getArgument(3));return true;});
        when(checkpoints.mutateExecutionCheckpoint(any(),anyString(),any())).thenAnswer(i->{String key=i.getArgument(0).toString()+i.getArgument(1);java.util.function.UnaryOperator<String> change=i.getArgument(2);String result=change.apply(saved.get(key));saved.put(key,result);return result;});
    }
    PythonCodeReuseService.Prepare request(String hash){return new PythonCodeReuseService.Prepare("request-1234","Calculate total","model-a","main.py",List.of(new PythonCodeReuseService.Reference("SCRIPT","script",1,hash,"main.py",null)),List.of());}
    @Test void freezesAuthoritativeVersionAndReadsDoNotGrantExecution(){
        var prepared=service.prepare(actor,request(PythonCodeProject.hash(content)));
        assertThat(prepared).containsEntry("executionAuthorized",false);assertThat((List<?>)prepared.get("sources")).singleElement().satisfies(s->assertThat(((PythonCodeReuseService.Source)s).content()).isEqualTo(content));
        verifyNoInteractions(coding,models);assertThat(service.prepare(actor,request(PythonCodeProject.hash(content)))).isEqualTo(prepared);
    }
    @Test void rejectsOtherOwnerAndInventedVersionsOrContent(){
        assertThatThrownBy(()->service.prepare(new PythonCodeReuseService.Actor("tenant","bob-id","bob"),request(PythonCodeProject.hash(content)))).hasMessageContaining("Authorized Python script unavailable");
        assertThatThrownBy(()->service.prepare(actor,request("0".repeat(64)))).hasMessageContaining("digest");verifyNoInteractions(coding,models);
    }
    @Test void requiresSeparateExplicitExecutionConsentBoundToSnapshot(){
        var prepared=service.prepare(actor,request(PythonCodeProject.hash(content)));String hash=(String)prepared.get("snapshotSha256");
        assertThatThrownBy(()->service.execute(actor,"request-1234",new PythonCodeReuseService.Execute(hash,false))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.execute(actor,"request-1234",new PythonCodeReuseService.Execute("0".repeat(64),true))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(coding,models);
    }
    @Test void refusesOldProposalAfterUserEditedSourceBeforeAnyWrite()throws Exception{
        service.prepare(actor,request(PythonCodeProject.hash(content)));
        var proposal=new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py",content.replace("7","8"))));
        String payload=json.writeValueAsString(proposal),wrapper=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String operation="cline-"+"a".repeat(64),hash=PythonCodeProject.hash(wrapper);
        when(artifacts.find(any(),anyString(),eq(operation))).thenReturn(Optional.of(Map.of("content",wrapper,"sha256",hash)));
        script.setCurrentVersion(2);script.setSourceCode("# user edit\n"+content);
        assertThatThrownBy(()->service.apply(actor,"request-1234",new PythonCodeReuseService.Apply(operation,hash,List.of("main.py"),"env"))).hasMessageContaining("Source changed");
        verifyNoInteractions(studio);
    }
    @Test void appliesOnlyReviewedDiffThroughExistingVersionedSave()throws Exception{
        service.prepare(actor,request(PythonCodeProject.hash(content)));
        var proposal=new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py",content.replace("7","8"))));
        String wrapper=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(json.writeValueAsBytes(proposal)));
        String operation="cline-"+"b".repeat(64),hash=PythonCodeProject.hash(wrapper);
        when(artifacts.find(any(),anyString(),eq(operation))).thenReturn(Optional.of(Map.of("content",wrapper,"sha256",hash)));
        when(studio.saveScript(anyString(),anyString(),any())).thenAnswer(i->{var request=(PythonDataScienceService.ScriptRequest)i.getArgument(2);var result=new PythonScriptEntity();result.setId(script.getId());result.setAssetId(script.getAssetId());result.setFileName(script.getFileName());result.setCurrentVersion(2);result.setSourceCode(request.sourceCode());return result;});
        service.apply(actor,"request-1234",new PythonCodeReuseService.Apply(operation,hash,List.of("main.py"),"env"));
        verify(studio).saveScript(eq("tenant"),eq("alice"),argThat(r->r.id().equals("script")&&r.expectedVersion()==1&&r.sourceCode().equals(content.replace("7","8"))));
        assertThat(json.valueToTree(service.status(actor,"request-1234",false)).path("applied").get(0).path("saved").get(0).path("version").asInt()).isEqualTo(2);
    }
    @Test void preservesAuthorizedFolderStructureAndRejectsRemapping(){
        var folder=new PythonScriptFolderEntity();folder.setId("pkg");folder.setName("pkg");script.setFolderId("pkg");
        when(folders.findFirstByIdAndTenantIdAndOwnerId("pkg","tenant","alice")).thenReturn(Optional.of(folder));
        assertThatThrownBy(()->service.prepare(actor,request(PythonCodeProject.hash(content)))).hasMessageContaining("hierarchy");
        var request=new PythonCodeReuseService.Prepare("request-1234","reuse","model-a","pkg/main.py",List.of(new PythonCodeReuseService.Reference("SCRIPT","script",1,PythonCodeProject.hash(content),"pkg/main.py",null)),List.of());
        assertThat(service.prepare(actor,request)).containsKey("snapshotSha256");
    }
    @Test void snippetsAreBoundToPreparedOwnerRunWithoutGrantingExecution(){
        var file=new UserAssetService.File("a".repeat(64),"CODE","main.py","text/x-python",PythonCodeProject.hash(content),content.length(),"AVAILABLE","source-task","source-run","snippet",1);
        when(assets.store(any(),eq(UserAssetService.Kind.CODE),anyString(),anyString(),anyString(),eq("main.py"),eq("text/x-python"),any())).thenReturn(file);
        var input=new PythonCodeReuseService.Prepare("request-snippet","reuse","model-a","main.py",List.of(new PythonCodeReuseService.Reference("SNIPPET",null,null,PythonCodeProject.hash(content),"main.py",content)),List.of());
        service.prepare(actor,input);
        verify(assets).store(eq(actor.owner()),eq(UserAssetService.Kind.CODE),eq("python-source-input:request-snippet"),startsWith("python-reuse-"),eq("python-studio-snippet:main.py"),eq("main.py"),eq("text/x-python"),any());
        verifyNoInteractions(coding,models);
    }
    @Test void anEmptyPackageMarkerIsARealSourceFileNotMissingContent(){
        var marker=new UserAssetService.File("b".repeat(64),"CODE","__init__.py","text/x-python",PythonCodeProject.hash(""),0,"AVAILABLE","source","run","snippet",1);
        when(assets.store(any(),eq(UserAssetService.Kind.CODE),anyString(),anyString(),anyString(),eq("__init__.py"),eq("text/x-python"),any())).thenReturn(marker);
        var input=new PythonCodeReuseService.Prepare("request-empty","Inspect the package","model-a","pkg/__init__.py",List.of(new PythonCodeReuseService.Reference("SNIPPET",null,null,PythonCodeProject.hash(""),"pkg/__init__.py","")),List.of());
        assertThat((List<?>)service.prepare(actor,input).get("sources")).singleElement().satisfies(s->assertThat(((PythonCodeReuseService.Source)s).content()).isEmpty());verifyNoInteractions(coding,models);
    }
    @Test void preparationBudgetIsRealProgressBeforeTheTaskIsAdmitted(){
        service.prepare(actor,request(PythonCodeProject.hash(content)));var status=service.status(actor,"request-1234",false);
        var scope=PythonCodeReuseService.scope(actor,"request-1234");saved.put(scope+"coding:analysis-tool-budget.v2:"+status.get("taskId"),"{}");
        assertThat(service.status(actor,"request-1234",false)).containsEntry("status","PREPARING").containsEntry("recoveryRequired",true);verifyNoInteractions(coding,models);
    }
    @Test void historicalAnalysisReferencesUseTheOwnedOperationVersionAndFileDigest()throws Exception{
        service.prepare(actor,request(PythonCodeProject.hash(content)));String operation="cline-"+"c".repeat(64),changed=content.replace("7","8");
        var project=new PythonCodeProject.Project("main.py",List.of(new PythonCodeProject.File("main.py",changed)));
        String source=PythonCodeProject.template().replace(PythonCodeProject.TOKEN,Base64.getEncoder().encodeToString(json.writeValueAsBytes(project)));
        when(artifacts.find(any(),anyString(),eq(operation))).thenReturn(Optional.of(Map.of("content",source,"script",Map.of("scriptVersion",2))));
        when(assets.store(any(),eq(UserAssetService.Kind.CODE),anyString(),anyString(),startsWith("python-studio-analysis:"),eq("main.py"),eq("text/x-python"),any()))
            .thenReturn(new UserAssetService.File("d".repeat(64),"CODE","main.py","text/x-python",PythonCodeProject.hash(changed),changed.length(),"AVAILABLE","source","run","history",1));
        var input=new PythonCodeReuseService.Prepare("request-history","reuse prior correction","model-a","main.py",List.of(new PythonCodeReuseService.Reference("ANALYSIS","request-1234:"+operation,2,PythonCodeProject.hash(changed),"main.py",null)),List.of());
        assertThat(((PythonCodeReuseService.Source)((List<?>)service.prepare(actor,input).get("sources")).get(0)).content()).isEqualTo(changed);
        assertThatThrownBy(()->service.prepare(new PythonCodeReuseService.Actor("tenant","bob-uuid","bob"),input)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
