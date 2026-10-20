package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.runtime.skill.api.discovery.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.resource.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillAnalysisContextServiceTest {
    final SkillRouter router = mock(SkillRouter.class);
    final SkillResolver resolver = mock(SkillResolver.class);
    final ConfigurableChatModelFactory models = mock(ConfigurableChatModelFactory.class);
    final SkillDefinition agent = mock(SkillDefinition.class);
    final InteractionRequest request = new InteractionRequest();
    final SkillDescriptor descriptor = new SkillDescriptor("method-a","v1","Compare","Compare cohorts","","DB","","","",1,Map.of());
    final AuthorizedSkillScope scope = new AuthorizedSkillScope(true,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    final ResolvedSkill skill = new ResolvedSkill(descriptor,"ORIGINAL_METHOD_NOT_A_JAVA_ANALYSIS_PLAN",List.of(),null,Map.of());
    @SuppressWarnings("unchecked") final SkillAnalysisContextService service = new SkillAnalysisContextService(router,resolver,models,new ObjectMapper(),mock(ObjectProvider.class));
    SkillAnalysisContextServiceTest() {
        request.setTenantId("t"); request.setUserId("u"); request.setQuery("Compare cohorts");
        when(agent.id()).thenReturn("agent"); when(agent.workflowConfig()).thenReturn(Map.of());
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(descriptor),"ROUTED",Map.of()));
        resolved(skill);
    }
    void resolved(ResolvedSkill value) {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(value,scope,"db","RESOLVED",Map.of()));
    }
    @SuppressWarnings("unchecked") Map<String,Object> delivered(Map<String,Object> result) {
        return ((List<Map<String,Object>>)result.get("skills")).get(0);
    }
    @Test void deliversOriginalAuthorizedInstructionsWithoutAModelOrCompiledMethods() {
        var result = service.prepare(request,agent,List.of("role"));
        assertThat(result).containsEntry("status","DELIVERED").containsEntry("stages",Map.of());
        assertThat(SkillAnalysisContext.validate(result)).isEqualTo(result);
        assertThat(delivered(result)).containsEntry("instructions",List.of(skill.instructions())).containsEntry("selectionOwner","CLINE");
        assertThat(SkillAnalysisContext.prompt(result,"PLAN")).isEmpty();
        verifyNoInteractions(models);
        verify(router).route(argThat(search -> search.roleContext().attributes().get("agentId").equals("agent")));
        verify(resolver,atLeast(3)).resolve(any());
    }
    @Test void deliveredCandidatesAreNotReportedAsAlreadyActivated() {
        var projection = SkillAnalysisContext.planningProjection(service.prepare(request,agent,List.of()),List.of("method-a"));
        assertThat(projection).containsEntry("loadedCount",1).containsEntry("activatedCount",0);
    }
    @Test void deniedSkillCannotReachTheCodingContext() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(null,null,"db","DENIED",Map.of()));
        var result = service.prepare(request,agent,List.of());
        assertThat(result).containsEntry("status","UNAVAILABLE").containsEntry("skills",List.of());
        verifyNoInteractions(models);
    }
    @Test void revocationBeforePublicationRejectsTheSnapshot() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(skill,scope,"db","RESOLVED",Map.of()))
            .thenReturn(new SkillResolution(null,null,"db","DENIED",Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
    }
    @Test void versionChangeCannotPublishOldInstructions() {
        var changed = new SkillDescriptor(descriptor.id(),"v2","changed","","","DB","","","",1,Map.of());
        resolved(new ResolvedSkill(changed,"new",List.of(),null,Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
    }
    @Test void changedInstructionsCannotBePublishedUnderTheOldFingerprint() {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(skill,scope,"db","RESOLVED",Map.of()))
            .thenReturn(new SkillResolution(new ResolvedSkill(descriptor,"changed",List.of(),null,Map.of()),scope,"db","RESOLVED",Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
    }
    @Test void emptyCatalogNeedsNoModelOrBusinessCapability() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"EMPTY",Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","NO_CANDIDATES");
        verifyNoInteractions(resolver,models);
    }
    @Test void sourceFailureRemainsExplicit() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"SOURCE_FAILED",Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
    }
    @Test void explicitSelectionConstrainsDiscoveryWithoutAnAutomaticFallback() {
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds",List.of("chosen")));
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"EMPTY",Map.of()));
        service.prepare(request,agent,List.of());
        verify(router,times(1)).route(argThat(search -> search.requestedSkillIds().equals(List.of("chosen"))));
        verifyNoInteractions(resolver,models);
    }
    @Test void searchMissUsesOnlyTheAuthorizedMetadataCatalog() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"EMPTY",Map.of()))
            .thenReturn(new SkillRouteResult(List.of(descriptor),"ROUTED",Map.of()));
        assertThat(service.prepare(request,agent,List.of("role"))).containsEntry("status","DELIVERED");
        verify(router).route(argThat(search -> search.query().isEmpty() && search.roleContext().tenantId().equals("t")));
        verifyNoInteractions(models);
    }
    @Test void cancellationBeforeDeliveryDoesNotCallSources() {
        request.setToolInput(Map.of("__agentCancellation",(java.util.function.BooleanSupplier)() -> true));
        assertThatThrownBy(() -> service.prepare(request,agent,List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(router,resolver,models);
    }
    @Test void cancellationDuringDeliveryCannotPublishAContext() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        request.setToolInput(Map.of("__agentCancellation",(java.util.function.BooleanSupplier)cancelled::get));
        when(resolver.resolve(any())).thenAnswer(call -> { cancelled.set(true); return new SkillResolution(skill,scope,"db","RESOLVED",Map.of()); });
        assertThatThrownBy(() -> service.prepare(request,agent,List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(models);
    }
    @Test void largeInstructionsAreChunkedLosslesslyForTheCodingContract() {
        String original = "x".repeat(7999)+"😀"+"y".repeat(9000);
        resolved(new ResolvedSkill(descriptor,original,List.of(),null,Map.of()));
        var result = delivered(service.prepare(request,agent,List.of()));
        @SuppressWarnings("unchecked") var chunks = (List<String>)result.get("instructions");
        assertThat(String.join("",chunks)).isEqualTo(original);
        assertThat(chunks).allMatch(text -> text.length() <= 8000);
    }
    @Test void oversizedInstructionsAreExplicitlyUnavailableRatherThanTruncated() {
        resolved(new ResolvedSkill(descriptor,"x".repeat(72001),List.of(),null,Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
    }
    @Test void verifiedReferenceTextIsDeliveredButScriptsAreNeverRead() throws Exception {
        byte[] text = "reference text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text));
        var reference = new SkillResourceDescriptor("ref","references/method.md","text/plain",hash,Map.of());
        var script = new SkillResourceDescriptor("code","scripts/run.py","text/plain",hash,Map.of());
        resolved(new ResolvedSkill(descriptor,skill.instructions(),List.of(reference,script),null,Map.of()));
        when(resolver.readResource(any())).thenReturn(Optional.of(new SkillResourceContent(reference,text)));
        var result = service.prepare(request,agent,List.of());
        assertThat(result).containsEntry("status","DELIVERED");
        assertThat(delivered(result).get("resources").toString()).contains("reference text","CONTENT_VERIFIED").doesNotContain("scripts/");
        verify(resolver,times(1)).readResource(argThat(input -> input.resourceId().equals("ref")));
    }
    @Test void unavailableReferenceRemainsALimitationWithoutFabricatingContent() {
        var reference = new SkillResourceDescriptor("ref","references/method.md","text/plain","a".repeat(64),Map.of());
        resolved(new ResolvedSkill(descriptor,skill.instructions(),List.of(reference),null,Map.of()));
        when(resolver.readResource(any())).thenReturn(Optional.empty());
        var result = service.prepare(request,agent,List.of());
        assertThat(result).containsEntry("status","DELIVERED_WITH_LIMITS");
        assertThat(delivered(result).get("resources").toString()).contains("UNAVAILABLE").doesNotContain("content=");
    }
    @Test void resourceContentMustMatchTheDeclaredDigest() {
        var reference = new SkillResourceDescriptor("ref","references/method.md","text/plain","a".repeat(64),Map.of());
        resolved(new ResolvedSkill(descriptor,skill.instructions(),List.of(reference),null,Map.of()));
        when(resolver.readResource(any())).thenReturn(Optional.of(new SkillResourceContent(reference,"altered".getBytes())));
        var result = service.prepare(request,agent,List.of());
        assertThat(result).containsEntry("status","DELIVERED_WITH_LIMITS");
        assertThat(delivered(result).toString()).doesNotContain("altered");
    }
}
