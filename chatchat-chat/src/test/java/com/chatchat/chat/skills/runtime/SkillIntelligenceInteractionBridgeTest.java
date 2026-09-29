package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.event.*;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.runtime.skill.api.execution.SkillCompositionRequest;
import com.chatchat.runtime.skill.application.SkillIntelligenceLayer;
import com.chatchat.runtime.skill.port.outbound.SkillIntentPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class SkillIntelligenceInteractionBridgeTest {
    @Test @SuppressWarnings("unchecked") void automaticallyEnablesOnlyForAuthorizedBoundSkills() {
        var bridge = new SkillIntelligenceInteractionBridge(mock(SkillIntelligenceLayer.class), mock(ObjectProvider.class));
        var router = mock(com.chatchat.runtime.skill.port.inbound.SkillRouter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(bridge, "router", router);
        var agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("agent"); when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds", List.of("skill")));
        var request = new InteractionRequest(); request.setQuery("Analyze"); request.setTenantId("tenant"); request.setUserId("user");
        var descriptor = new com.chatchat.runtime.skill.api.skill.SkillDescriptor("skill", "v1", "Skill", "", "finance", "DB", "", "", "", 1,
            Map.of("executionEngine", "GOOGLE_ADK_NATIVE", "executionModel", "bound-model"));
        when(router.route(any())).thenReturn(new com.chatchat.runtime.skill.api.discovery.SkillRouteResult(List.of(descriptor), "ROUTED", Map.of()));
        assertThat(bridge.enabled(request, agent, List.of("role"))).isTrue();
        when(router.route(any())).thenReturn(new com.chatchat.runtime.skill.api.discovery.SkillRouteResult(List.of(), "EMPTY", Map.of()));
        assertThat(bridge.enabled(request, agent, List.of("role"))).isFalse();
        when(agent.workflowConfig()).thenReturn(Map.of("boundDomainSkillIds", List.of("skill"),
            "skillIntelligenceEngine", "GOOGLE_ADK_NATIVE"));
        assertThat(bridge.enabled(request, agent, List.of("role"))).isTrue();
        assertThat(bridge.available(request, agent, List.of("role"))).isFalse();
    }
    @Test @SuppressWarnings("unchecked") void preservesAgentScopeAndPublishesTaskStages() {
        var intelligence = mock(SkillIntelligenceLayer.class);
        var publisher = mock(AgentRunEventPublisher.class);
        ObjectProvider<AgentRunEventPublisher> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(publisher));
        var bridge = new SkillIntelligenceInteractionBridge(intelligence, providers);
        var agent = mock(SkillDefinition.class);
        when(agent.id()).thenReturn("agent"); when(agent.modelName()).thenReturn("model");
        when(agent.workflowConfig()).thenReturn(Map.of("skillIntelligenceEngine", "GOOGLE_ADK_NATIVE", "boundDomainSkillIds", List.of("skill")));
        var request = new InteractionRequest(); request.setQuery("Analyze"); request.setTenantId("tenant"); request.setUserId("user");
        request.setToolInput(Map.of("__agentRunId", "run", "skillDataInputs", Map.of("portfolio", "p")));
        when(intelligence.execute(any(), any())).thenAnswer(call -> {
            SkillCompositionRequest actual = call.getArgument(0);
            assertThat(actual.identity().attributes()).containsEntry("agentId", "agent");
            assertThat(actual.identity().tenantId()).isEqualTo("tenant");
            assertThat(actual.skillIds()).containsExactly("skill");
            assertThat(actual.inputs()).containsOnlyKeys("portfolio");
            Consumer<SkillIntelligenceLayer.Event> observer = call.getArgument(1);
            observer.accept(new SkillIntelligenceLayer.Event("SKILL_EVALUATED", 1, "skill", "LIMITED"));
            return new SkillIntelligenceLayer.Result("COMPLETED_WITH_LIMITATIONS", "USER_INPUT_REQUIRED",
                new SkillIntentPlanner.Intent("finance", "Analyze", List.of(), "MODEL"), Map.of(), List.of("summary"), List.of(), 10);
        });
        var response = bridge.execute(request, InteractionContext.builder().requestId("req").conversationId("conv").build(), agent, List.of("role"));
        assertThat(response.getAnswer()).contains("USER_INPUT_REQUIRED");
        assertThat(((com.chatchat.common.runtime.capability.WorkflowOutcome) response.getMetadata()
            .get("workflowOutcome")).publicStatus()).isEqualTo("NO_PRESENTABLE_RESULT");
        verify(publisher).publish(argThat(event -> "run".equals(event.runId()) && event.type() == AgentRunEventType.OBSERVATION_RECORDED
            && "SKILL_EVALUATED".equals(event.payload().get("stage"))));
    }
}
