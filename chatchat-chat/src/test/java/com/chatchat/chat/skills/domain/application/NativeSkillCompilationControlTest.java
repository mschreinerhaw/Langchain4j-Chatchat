package com.chatchat.chat.skills.domain.application;

import com.chatchat.chat.skills.domain.adapter.*;
import com.chatchat.chat.skills.domain.artifact.*;
import com.chatchat.chat.skills.domain.catalog.*;
import com.chatchat.chat.skills.domain.indexing.*;
import com.chatchat.chat.skills.domain.source.*;
import com.chatchat.common.mcp.license.McpLicenseEntitlementPort;
import com.chatchat.common.retrieval.ResourceAuthorizationPort;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.resolution.SkillResolutionRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeSkillCompilationControlTest {
    final DomainSkillRepository repo=mock(DomainSkillRepository.class);
    final ExternalSkillAdapterGateway compiler=mock(ExternalSkillAdapterGateway.class);
    final DomainSkillArtifactStore store=mock(DomainSkillArtifactStore.class);
    final DomainSkillPackageReader reader=mock(DomainSkillPackageReader.class);
    final DomainSkillIndexService index=mock(DomainSkillIndexService.class);
    final DomainSkillService service=new DomainSkillService(repo,mock(DomainSkillCategoryRepository.class),
        mock(McpLicenseEntitlementPort.class),index,mock(DomainSkillRemoteImporter.class),compiler,store,reader);
    final String raw="---\nname: native-example\ndescription: Analyze delivered rows\nlicense: MIT\n---\n# Original\r\nDo not rewrite.\n";
    NativeSkillCompilationControlTest() {
        when(repo.save(any())).thenAnswer(c->c.getArgument(0)); when(repo.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));
        when(store.storeOriginal(anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString())).thenReturn("original-id");
        when(reader.nativePackage(anyString(),anyString(),eq("original-id"))).thenReturn(NativeSkillPackage.inspect(raw.getBytes(StandardCharsets.UTF_8),false));
    }
    DomainSkillEntity imported(Boolean compile) {
        return service.importFile("tenant","admin",raw.getBytes(StandardCharsets.UTF_8),"SKILL.md","","General",new DomainSkillService.ImportOptions(compile,"NATIVE"));
    }
    void owned(DomainSkillEntity s) {
        when(repo.findByIdAndTenantId(s.getId(),"tenant")).thenReturn(Optional.of(s));
        when(repo.findVisibleById("tenant",s.getId())).thenReturn(Optional.of(s));
    }
    @Test void nullOldFieldRetainsLegacyBehaviorAndNativeImportDefaultsOff() {
        assertThat(new DomainSkillEntity().participatesInRuntimeCompilation()).isTrue();
        var s=imported(null); assertThat(s.getRuntimeCompilationEnabled()).isFalse();
        assertThat(s.getMarkdownContent()).isEqualTo(raw); assertThat(s.getNativeSourceId()).isEqualTo("original-id");
        assertThat(s.getNativeMetadataJson()).contains(NativeSkillPackage.sha256(raw.getBytes(StandardCharsets.UTF_8)));
        verifyNoInteractions(compiler); verify(store).storeOriginal(eq("tenant"),eq(s.getId()),eq("NATIVE_MARKDOWN"),eq("SKILL.md"),anyString(),eq(raw.getBytes(StandardCharsets.UTF_8)),anyString());
    }
    @Test void compilerFailureRetainsOriginalAndStillResolvesForAgentButNotJava() {
        when(compiler.adaptAndCompile(any())).thenThrow(new IllegalArgumentException("Compiler unavailable"));
        var s=imported(true); owned(s); s.setStatus("PUBLISHED");
        assertThat(s.getCompilationStatus()).isEqualTo("FAILED"); assertThat(s.getCompilationError()).contains("unavailable");
        assertThat(s.getMarkdownContent()).isEqualTo(raw);
        var grants=mock(ResourceAuthorizationPort.class);
        when(grants.explicitlyAllowedIdsForAgent(anyString(),anyString(),anyString(),anySet(),anySet(),eq("agent"))).thenAnswer(c->c.getArgument(4));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"resourceAuthorization",grants);
        var nativeRole=new SkillRoleContext("tenant","user",List.of(),List.of(),Map.of("agentId","agent","skillPurpose","AGENT_NATIVE"));
        when(reader.nativeResources(any())).thenReturn(List.of());
        assertThat(service.resolve(new SkillResolutionRequest(s.getId(),"",nativeRole))).get().extracting(x->x.instructions()).isEqualTo(raw);
        var javaRole=new SkillRoleContext("tenant","user",List.of(),List.of(),Map.of("agentId","agent"));
        assertThat(service.resolve(new SkillResolutionRequest(s.getId(),"",javaRole))).isEmpty();
    }
    @Test void toggleDoesNotRewriteOrReimportOriginalAndDisabledManualCompileIsRejected() {
        var s=imported(false); owned(s); clearInvocations(store);
        service.save("tenant","admin",new DomainSkillService.SaveSkillCommand(s.getId(),"Display label","General","",raw,false));
        assertThat(s.getMarkdownContent()).isEqualTo(raw); assertThat(s.getNativeSourceId()).isEqualTo("original-id");
        assertThatThrownBy(()->service.compileProtocol("tenant",s.getId(),Map.of())).hasMessageContaining("disabled");
        assertThatThrownBy(()->service.recompileProtocol("tenant",s.getId())).hasMessageContaining("disabled");
        assertThatThrownBy(()->service.save("tenant","admin",new DomainSkillService.SaveSkillCommand(s.getId(),"Name","General","",raw+"changed",false)))
            .hasMessageContaining("immutable");
        verifyNoInteractions(compiler); verifyNoInteractions(store);
    }
    @Test void existingSystemSkillOmittedFlagDoesNotResetExplicitFalse() {
        var s=new DomainSkillEntity(); s.setId("legacy"); s.setTenantId("tenant"); s.setRuntimeCompilationEnabled(false); owned(s);
        service.save("tenant","admin",new DomainSkillService.SaveSkillCommand("legacy","Name","General","","# Content"));
        assertThat(s.getRuntimeCompilationEnabled()).isFalse(); verifyNoInteractions(compiler);
    }
}
