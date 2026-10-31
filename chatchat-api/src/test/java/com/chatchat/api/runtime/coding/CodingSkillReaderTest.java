package com.chatchat.api.runtime.coding;

import com.chatchat.agents.runtime.coding.*;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.retrieval.SkillExecutionScopePort;
import com.chatchat.common.runtime.coding.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.resource.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.SkillResolver;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingSkillReaderTest {
    final SkillResolver resolver=mock(SkillResolver.class);
    final SkillExecutionScopePort scopes=mock(SkillExecutionScopePort.class);
    final DurableCodingTaskService tasks=mock(DurableCodingTaskService.class);
    final CodingTaskContract contract=mock(CodingTaskContract.class);
    final CodingTaskContract.Authorization authority=mock(CodingTaskContract.Authorization.class);
    final CodingJournal.Lease lease=new CodingJournal.Lease("worker",java.time.Instant.MAX,1);
    final Map<String,JsonNode> saved=new HashMap<>();
    final ObjectMapper json=new ObjectMapper();
    final CodingSkillReader reader=new CodingSkillReader(resolver,scopes,tasks,json);
    final SkillDescriptor descriptor=new SkillDescriptor("method","v1","Comparison","Method","","DB","","","",1,Map.of());
    final AuthorizedSkillScope allowed=new AuthorizedSkillScope(true,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    CodingSkillReaderTest() {
        when(contract.schemaVersion()).thenReturn(CodingProtocol.ANALYSIS_VERSION);
        when(contract.taskId()).thenReturn("task");
        when(contract.scope()).thenReturn(new KernelDataScope("tenant","owner",null,null,"run",null,Map.of()));
        when(contract.authorization()).thenReturn(authority);
        when(authority.allowedTools()).thenReturn(List.of("skill_read"));
        when(contract.runtimeContext()).thenReturn(Map.of("skills",Map.of("skills",List.of(Map.of(
            "id","method","version","v1","agentId","agent")))));
        when(scopes.resolve(anyString(),anyString(),anyString(),anyList(),anyList())).thenReturn(
            new SkillExecutionScopePort.EffectiveScope(List.of(),List.of(),List.of("role"),true,true));
        resolve("ORIGINAL_METHOD",List.of());
        when(tasks.privateState(any(),anyString(),anyString())).thenAnswer(call -> saved.get(call.getArgument(1)+"\0"+call.getArgument(2)));
        doAnswer(call -> { saved.put(call.getArgument(1)+"\0"+call.getArgument(3),call.getArgument(5)); return null; })
            .when(tasks).privateState(any(),anyString(),eq(lease),anyString(),isNull(),any());
    }
    void resolve(String instructions,List<SkillResourceDescriptor> resources) {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(
            new ResolvedSkill(descriptor,instructions,resources,null,Map.of()),allowed,"db","RESOLVED",Map.of()));
    }
    @Test void readsOnlyRequestedInstructionsUsingLiveOwnerAndAgentAuthorization() {
        assertThat(reader.read(contract,lease,"method","")).containsEntry("instructions","ORIGINAL_METHOD");
        verify(scopes).resolve("tenant","owner","agent",List.of(),List.of());
        verify(resolver).resolve(argThat(r -> r.version().equals("v1") && r.roleContext().tenantId().equals("tenant")
            && r.roleContext().userId().equals("owner") && r.roleContext().attributes().get("agentId").equals("agent")));
        verify(resolver,never()).readResource(any());
        assertThat(saved).hasSize(2);
    }
    @Test void anotherSkillOrMissingToolGrantCannotResolveContent() {
        assertThatThrownBy(() -> reader.read(contract,lease,"other","")).isInstanceOf(SecurityException.class);
        when(authority.allowedTools()).thenReturn(List.of());
        assertThatThrownBy(() -> reader.read(contract,lease,"method","")).isInstanceOf(SecurityException.class);
        verifyNoInteractions(resolver,scopes,tasks);
    }
    @Test void permissionRevocationIsRecheckedEvenAfterTheFirstRead() {
        reader.read(contract,lease,"method","");
        when(scopes.resolve(anyString(),anyString(),anyString(),anyList(),anyList()))
            .thenReturn(SkillExecutionScopePort.EffectiveScope.denied(List.of()));
        assertThatThrownBy(() -> reader.read(contract,lease,"method","")).isInstanceOf(SecurityException.class);
        verify(resolver,times(1)).resolve(any());
    }
    @Test void skillRevocationOrVersionChangeCannotUseTheFrozenCatalog() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(null,null,"db","DENIED",Map.of()));
        assertThatThrownBy(() -> reader.read(contract,lease,"method","")).isInstanceOf(SecurityException.class);
        var changed=new SkillDescriptor("method","v2","Changed","","","DB","","","",1,Map.of());
        when(resolver.resolve(any())).thenReturn(new SkillResolution(
            new ResolvedSkill(changed,"Changed",List.of(),null,Map.of()),allowed,"db","RESOLVED",Map.of()));
        assertThatThrownBy(() -> reader.read(contract,lease,"method","")).isInstanceOf(SecurityException.class);
    }
    @Test void resumedReadsKeepTheTaskSnapshotWhenPublishedContentChanges() {
        reader.read(contract,lease,"method","");
        resolve("ALTERED_METHOD",List.of());
        assertThat(reader.read(contract,lease,"method","")).containsEntry("instructions","ORIGINAL_METHOD");
    }
    @Test void catalogIsFrozenBeforeInferenceAndNewTasksReceiveTheNewVersion() {
        reader.freezeCatalog(contract,lease);
        var next=new SkillDescriptor("method","v2","Comparison","Method","","DB","","","",1,Map.of());
        when(resolver.resolve(any())).thenReturn(new SkillResolution(
            new ResolvedSkill(next,"UPDATED_METHOD",List.of(),null,Map.of()),allowed,"db","RESOLVED",Map.of()));
        assertThat(reader.read(contract,lease,"method","")).containsEntry("instructions","ORIGINAL_METHOD").containsEntry("version","v1");
        when(contract.taskId()).thenReturn("next-task");
        when(contract.runtimeContext()).thenReturn(Map.of("skills",Map.of("skills",List.of(Map.of("id","method","version","v2","agentId","agent")))));
        assertThat(reader.read(contract,lease,"method","")).containsEntry("instructions","UPDATED_METHOD").containsEntry("version","v2");
    }
    @Test void aDynamicCollisionCannotLoadContentOrBlockTheImageDefault() {
        when(contract.runtimeContext()).thenReturn(Map.of("skills",Map.of("skills",List.of(Map.of(
            "id","method","version","v999","name","Python-Data-Analysis","agentId","agent")))));
        reader.freezeCatalog(contract,lease);
        verifyNoInteractions(resolver,scopes,tasks);
    }
    @Test void resourceIsLoadedOnlyOnDemandAndHashVerified() throws Exception {
        byte[] bytes="REFERENCE_CONTENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var ref=new SkillResourceDescriptor("reference","references/method.md","text/plain",digest,Map.of());
        resolve("ORIGINAL_METHOD",List.of(ref));
        when(resolver.readResource(any())).thenReturn(Optional.of(new SkillResourceContent(ref,bytes)));
        reader.read(contract,lease,"method","");
        assertThat(reader.read(contract,lease,"method",ref.relativePath())).containsEntry("content","REFERENCE_CONTENT");
        when(resolver.readResource(any())).thenReturn(Optional.of(new SkillResourceContent(ref,"altered".getBytes())));
        assertThat(reader.read(contract,lease,"method",ref.relativePath())).containsEntry("content","REFERENCE_CONTENT");
    }
    @Test void scriptsTraversalAndUndeclaredResourcesCannotBeRead() {
        for (String path : List.of("scripts/run.py","../secret","references/../../secret","unknown.md","C:\\secret"))
            assertThatThrownBy(() -> reader.read(contract,lease,"method",path)).isInstanceOf(SecurityException.class);
        verify(resolver,never()).readResource(any());
    }
    @Test void oversizedInstructionsFailExplicitlyWithoutTruncation() {
        resolve("x".repeat(72001),List.of());
        assertThatThrownBy(() -> reader.read(contract,lease,"method","")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nativeScriptsAndBinaryAssetsAreFrozenExactlyWithoutExecutionAuthority() throws Exception {
        byte[] binary=new byte[]{0,(byte)255,13,10}, script="def sample():\n    return 1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(binary));
        String scriptHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(script));
        var asset=new SkillResourceDescriptor("original/assets/data.bin","assets/data.bin","application/octet-stream",hash,Map.of());
        var code=new SkillResourceDescriptor("original/scripts/check.py","scripts/check.py","text/plain",scriptHash,Map.of());
        String original="---\nname: native-sample\ndescription: Analyze data\nlicense: MIT\n---\n# Original\r\n";
        when(resolver.resolve(any())).thenReturn(new SkillResolution(new ResolvedSkill(descriptor,original,List.of(asset,code),null,
            Map.of("nativeOriginal",true)),allowed,"db","RESOLVED",Map.of()));
        when(resolver.readResource(any())).thenAnswer(c -> {
            SkillResourceRequest request=c.getArgument(0);
            return Optional.of(request.resourceId().equals(asset.resourceId())?new SkillResourceContent(asset,binary):new SkillResourceContent(code,script));
        });
        reader.freezeCatalog(contract,lease);
        assertThat(reader.read(contract,lease,"method","SKILL.md")).containsEntry("instructions",original).containsEntry("nativeOriginal",true);
        var frozen=reader.read(contract,lease,"method","assets/data.bin");
        assertThat(frozen).containsEntry("encoding","base64").containsEntry("sha256",hash);
        assertThat(Base64.getDecoder().decode((String)frozen.get("content"))).isEqualTo(binary);
        assertThat(reader.read(contract,lease,"method","scripts/check.py")).containsEntry("content",new String(script,java.nio.charset.StandardCharsets.UTF_8));
        clearInvocations(resolver);
        reader.read(contract,lease,"method","scripts/check.py");
        verify(resolver,never()).readResource(any());
        verify(authority,never()).allowedSideEffects();
    }
}
