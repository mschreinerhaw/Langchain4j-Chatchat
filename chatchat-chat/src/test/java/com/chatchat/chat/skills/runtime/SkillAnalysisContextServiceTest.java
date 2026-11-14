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
        when(agent.id()).thenReturn("agent"); when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds",List.of("method-a")));
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(descriptor),"ROUTED",Map.of()));
        resolved(skill);
    }
    void resolved(ResolvedSkill value) {
        when(resolver.resolve(any())).thenReturn(new SkillResolution(value,scope,"db","RESOLVED",Map.of()));
    }
    @SuppressWarnings("unchecked") Map<String,Object> delivered(Map<String,Object> result) {
        return ((List<Map<String,Object>>)result.get("skills")).get(0);
    }
    @Test void disabledJavaPreparationCopiesAuthorizationReferencesWithoutDiscoveryOrContentReads() {
        service.setJavaSkillsEnabled(false);
        var result=service.prepare(request,agent,List.of("role"));
        assertThat(result.get("status")).isEqualTo("DEFERRED_TO_CLINE");
        assertThat(delivered(result)).containsEntry("id","method-a").containsEntry("version","ON_DEMAND");
        verifyNoInteractions(router,resolver,models);
    }
    @Test void bindingRegistersOnlyCapabilityCardsWithoutResolvingContentOrCallingAModel() {
        var result=service.prepare(request,agent,List.of("role"));
        assertThat(result).containsEntry("status","AVAILABLE").containsEntry("stages",Map.of());
        assertThat(SkillAnalysisContext.validate(result)).isEqualTo(result);
        assertThat(delivered(result)).containsEntry("id","method-a").containsEntry("version","v1")
            .containsEntry("agentId","agent").containsEntry("contentDelivery","ON_DEMAND")
            .doesNotContainKeys("instructions","resources");
        assertThat(result.toString()).doesNotContain(skill.instructions());
        verifyNoInteractions(resolver,models);
        verify(router).route(argThat(search -> search.roleContext().attributes().get("agentId").equals("agent")
            && search.query().isEmpty() && search.requestedSkillIds().equals(List.of("method-a"))));
    }
    @Test void availableCandidatesAreNeitherLoadedNorActivated() {
        var projection=SkillAnalysisContext.planningProjection(service.prepare(request,agent,List.of()),List.of("method-a"));
        assertThat(projection).containsEntry("loadedCount",0).containsEntry("activatedCount",0);
    }
    @Test void allAuthorizedBindingsSurviveBothTheFormerSixSkillCapAndTheMetadataQueryBound() {
        var ids=java.util.stream.IntStream.range(0,123).mapToObj(i->"method-"+i).toList();
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds",ids));
        when(router.route(any())).thenAnswer(call->{
            SkillSearchRequest search=call.getArgument(0);
            var cards=new ArrayList<SkillDescriptor>();
            for(String id:search.requestedSkillIds()) cards.add(new SkillDescriptor(id,"v1",id,"Purpose "+id,"","DB","","","",1,Map.of()));
            cards.add(descriptor); // A source cannot add an unrequested identity.
            return new SkillRouteResult(cards,"ROUTED",Map.of());
        });
        var result=service.prepare(request,agent,List.of("role"));
        assertThat(result).containsEntry("status","AVAILABLE");
        @SuppressWarnings("unchecked") var cards=(List<Map<String,Object>>)result.get("skills");
        assertThat(cards).extracting(c->c.get("id")).containsExactlyElementsOf(ids);
        assertThat(SkillAnalysisContext.validate(result)).isEqualTo(result);
        var queries=org.mockito.ArgumentCaptor.forClass(SkillSearchRequest.class);
        verify(router,times(2)).route(queries.capture());
        assertThat(queries.getAllValues()).extracting(q->q.requestedSkillIds().size()).containsExactly(100,23);
        assertThat(queries.getAllValues()).allSatisfy(q->{
            assertThat(q.query()).isEmpty();assertThat(q.limit()).isEqualTo(q.requestedSkillIds().size());
            assertThat(q.roleContext().attributes()).containsEntry("agentId","agent");
        });
        verifyNoInteractions(resolver,models);
    }
    @Test void aFailedMetadataBatchIsExplicitRatherThanReportedAsACompleteCatalog() {
        var ids=java.util.stream.IntStream.range(0,101).mapToObj(i->"method-"+i).toList();
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds",ids));
        when(router.route(any())).thenAnswer(call->{
            SkillSearchRequest search=call.getArgument(0);
            return search.requestedSkillIds().contains("method-100")
                ?new SkillRouteResult(List.of(),"SOURCE_FAILED",Map.of())
                :new SkillRouteResult(List.of(new SkillDescriptor("method-0","v1","First","Purpose","","DB","","","",1,Map.of())),"ROUTED",Map.of());
        });
        var result=service.prepare(request,agent,List.of());
        assertThat(result).containsEntry("status","AVAILABLE_WITH_LIMITS");
        assertThat((List<?>)result.get("skills")).hasSize(1);
        verify(router,times(2)).route(any());verifyNoInteractions(resolver,models);
    }
    @Test void sourceFailureRemainsExplicit() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"SOURCE_FAILED",Map.of()));
        assertThat(service.prepare(request,agent,List.of())).containsEntry("status","UNAVAILABLE");
        verifyNoInteractions(resolver,models);
    }
    @Test void explicitBindingConstrainsDiscoveryWithoutFallback() {
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds",List.of("chosen","builtin:python-data-analysis")));
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(),"EMPTY",Map.of()));
        service.prepare(request,agent,List.of());
        verify(router,times(1)).route(argThat(search -> search.requestedSkillIds().equals(List.of("chosen"))));
        verifyNoInteractions(resolver,models);
    }
    @Test void unboundAgentNeverReceivesAnotherAgentsDynamicSkills() {
        when(agent.workflowConfig()).thenReturn(Map.of("disabledBuiltinSkillIds",List.of("builtin:python-data-analysis")));
        var context=service.prepare(request,agent,List.of("role"));
        assertThat(context).containsEntry("status","NO_CANDIDATES");
        assertThat(SkillAnalysisContext.catalog(context)).doesNotContainKey("builtinSkillPolicy");
        verifyNoInteractions(router,resolver,models);
    }
    @Test void cancellationBeforeBindingDoesNotCallSources() {
        request.setToolInput(Map.of("__agentCancellation",(java.util.function.BooleanSupplier)() -> true));
        assertThatThrownBy(() -> service.prepare(request,agent,List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(router,resolver,models);
    }
    @Test void cancellationDuringDiscoveryCannotPublishACatalog() {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        request.setToolInput(Map.of("__agentCancellation",(java.util.function.BooleanSupplier)cancelled::get));
        when(router.route(any())).thenAnswer(call -> { cancelled.set(true); return new SkillRouteResult(List.of(descriptor),"ROUTED",Map.of()); });
        assertThatThrownBy(() -> service.prepare(request,agent,List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(resolver,models);
    }
}
