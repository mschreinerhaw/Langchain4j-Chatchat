package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.agents.runtime.context.SkillAnalysisContext;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.runtime.skill.api.discovery.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.*;
import com.chatchat.runtime.skill.port.inbound.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillAnalysisContextServiceTest {
    final SkillRouter router = mock(SkillRouter.class);
    final SkillResolver resolver = mock(SkillResolver.class);
    final ConfigurableChatModelFactory models = mock(ConfigurableChatModelFactory.class);
    final ChatModel model = mock(ChatModel.class);
    final SkillDefinition agent = mock(SkillDefinition.class);
    final InteractionRequest request = new InteractionRequest();
    final SkillDescriptor descriptor = new SkillDescriptor("method-a", "v1", "Compare", "Compare cohorts", "", "DB", "", "", "", 1, Map.of());
    final ResolvedSkill skill = new ResolvedSkill(descriptor, "METHOD_BODY_COMPARE_SAME_SCOPE", List.of(), null, Map.of());
    final AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    @SuppressWarnings("unchecked") final SkillAnalysisContextService service = new SkillAnalysisContextService(router, resolver, models, new ObjectMapper(), mock(ObjectProvider.class));
    SkillAnalysisContextServiceTest() {
        request.setTenantId("t"); request.setUserId("u"); request.setQuery("Compare cohorts");
        when(agent.id()).thenReturn("agent"); when(agent.modelName()).thenReturn("model"); when(agent.workflowConfig()).thenReturn(Map.of());
        when(models.create("model")).thenReturn(model);
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()));
        when(resolver.resolve(any())).thenReturn(new SkillResolution(skill, scope, "db", "RESOLVED", Map.of()));
    }
    String json(String alias) {
        return "{\"activatedSkills\":[\"" + alias + "\"],\"stages\":{\"PLAN\":[\"compare scope\"],"
            + "\"ACQUISITION\":[\"scope evidence\"],\"ANALYSIS\":[\"compare cohorts\"],\"VALIDATION\":[\"check denominators\"],\"REPORT\":[\"disclose limitations\"]}}";
    }
    ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    ChatResponse load() {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("load-1").name("load_skill")
            .arguments("{\"skill_name\":\"" + AdkAnalysisSkillSource.alias(descriptor.id()) + "\"}").build())).build();
    }
    @Test void realAdkLoadsInstructionsAndCompilesAllStagesFromAuthorizedAutomaticDiscovery() {
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            assertThat(((ChatRequest) call.getArgument(0)).messages().toString()).doesNotContain("METHOD_BODY");
            return load();
        }).thenAnswer(call -> {
            assertThat(((ChatRequest) call.getArgument(0)).messages().toString()).contains("METHOD_BODY_COMPARE_SAME_SCOPE");
            return answer(json(AdkAnalysisSkillSource.alias(descriptor.id())));
        });
        var result = service.prepare(request, agent, List.of("role"));
        assertThat(result).containsEntry("status", "APPLIED");
        assertThat(SkillAnalysisContext.validate(result)).isEqualTo(result);
        assertThat(result.get("stages").toString()).contains("check denominators", "disclose limitations");
        verify(router).route(argThat(search -> search.requestedSkillIds().isEmpty() && search.roleContext().attributes().get("agentId").equals("agent")));
        verify(resolver, atLeast(2)).resolve(argThat(resolve -> resolve.skillId().equals(descriptor.id())));
    }
    @Test void cannotClaimUnloadedSkillWasApplied() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))));
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "UNAVAILABLE");
        verifyNoInteractions(resolver);
    }
    @Test void repairsClaimedSkillBeforePublishingMethodology() {
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))))
            .thenReturn(load())
            .thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))));

        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "APPLIED");
        verify(model, times(3)).chat(any(ChatRequest.class));
        verify(resolver, atLeast(2)).resolve(any());
    }
    @Test void repairsWrongAliasAfterInstructionsWereLoaded() {
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(load())
            .thenReturn(answer(json(descriptor.id())))
            .thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))));

        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "APPLIED");
        verify(model, times(3)).chat(any(ChatRequest.class));
    }
    @Test void emptyCatalogDoesNotCallModelOrRequireMcp() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(), "EMPTY", Map.of()));
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "NO_CANDIDATES");
        verifyNoInteractions(models, resolver);
    }
    @Test void selectedIdsConstrainDiscoveryWithoutGrantingAccess() {
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds", List.of("chosen")));
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(), "EMPTY", Map.of()));
        service.prepare(request, agent, List.of());
        verify(router).route(argThat(search -> search.requestedSkillIds().equals(List.of("chosen"))));
        verifyNoInteractions(resolver, models);
    }
    @Test void noRelevantSkillsLeavesGeneralAnswerAvailable() {
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"activatedSkills\":[],\"stages\":{}}"));
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "NO_RELEVANT_SKILL");
        verifyNoInteractions(resolver);
    }
    @Test void revocationBeforePublicationRejectsContext() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load()).thenAnswer(call -> {
            when(resolver.resolve(any())).thenReturn(new SkillResolution(null, null, "db", "DENIED", Map.of()));
            return answer(json(AdkAnalysisSkillSource.alias(descriptor.id())));
        });
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "UNAVAILABLE");
    }
    @Test void incompleteLifecycleIsNotReportedAsApplied() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load()).thenReturn(answer("{\"activatedSkills\":[\""
            + AdkAnalysisSkillSource.alias(descriptor.id()) + "\"],\"stages\":{\"PLAN\":[\"only plan\"]}}"));
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "UNAVAILABLE");
    }
    @Test void parentWaitsUntilAdkModelSettles() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout");
            return answer("{\"activatedSkills\":[],\"stages\":{}}");
        });
        try {
            var pending = executor.submit(() -> service.prepare(request, agent, List.of()));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(pending.isDone()).isFalse();
            release.countDown();
            assertThat(pending.get(5, java.util.concurrent.TimeUnit.SECONDS)).containsEntry("status", "NO_RELEVANT_SKILL");
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void cancellationIsNotConvertedIntoAnUnavailableSkill() {
        request.setToolInput(Map.of("__agentCancellation", (java.util.function.BooleanSupplier) () -> true));
        assertThatThrownBy(() -> service.prepare(request, agent, List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(router, models);
    }
    @Test void searchMissFallsBackToAuthorizedMetadataCatalog() {
        when(router.route(any())).thenReturn(new SkillRouteResult(List.of(), "EMPTY", Map.of()))
            .thenReturn(new SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()));
        when(model.chat(any(ChatRequest.class))).thenReturn(load()).thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))));
        assertThat(service.prepare(request, agent, List.of("role"))).containsEntry("status", "APPLIED");
        verify(router).route(argThat(search -> search.query().isEmpty() && search.roleContext().tenantId().equals("t")
            && search.roleContext().attributes().get("agentId").equals("agent")));
    }
    @Test void repairsOnlyOutputFormatWithinSameAdkSession() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load()).thenReturn(answer("Here is the methodology."))
            .thenAnswer(call -> {
                assertThat(((ChatRequest) call.getArgument(0)).messages().toString())
                    .contains("METHOD_BODY_COMPARE_SAME_SCOPE", "previous reply did not satisfy");
                return answer(json(AdkAnalysisSkillSource.alias(descriptor.id())));
            });
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "APPLIED");
        verify(model, times(3)).chat(any(ChatRequest.class));
    }
    @Test void explicitlyBoundMethodologyMustBeInspectedBeforeDeclaringIrrelevance() {
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds", List.of(descriptor.id())));
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("{\"activatedSkills\":[],\"stages\":{}}"))
            .thenReturn(load()).thenReturn(answer(json(AdkAnalysisSkillSource.alias(descriptor.id()))));
        assertThat(service.prepare(request, agent, List.of())).containsEntry("status", "APPLIED");
        verify(model, times(3)).chat(any(ChatRequest.class));
    }
    @Test void followUpIntentReceivesBoundedConversationContextWithoutTreatingItAsEvidence() {
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> {
            assertThat(((ChatRequest) call.getArgument(0)).messages().toString()).contains("EARLIER_COMPARISON_GOAL", "currentEvidence");
            return answer("{\"activatedSkills\":[],\"stages\":{}}");
        });
        var conversation = com.chatchat.chat.interaction.model.InteractionContext.builder()
            .conversationSummary("EARLIER_COMPARISON_GOAL").build();
        assertThat(service.prepare(request, agent, List.of(), conversation)).containsEntry("status", "NO_RELEVANT_SKILL");
    }
    @Test void cancellationDuringModelInferenceCannotPublishAppliedContext() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        request.setToolInput(Map.of("__agentCancellation", (java.util.function.BooleanSupplier) cancelled::get));
        when(model.chat(any(ChatRequest.class))).thenAnswer(call -> { cancelled.set(true); return answer("{\"activatedSkills\":[],\"stages\":{}}"); });
        assertThatThrownBy(() -> service.prepare(request, agent, List.of())).isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
