package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.answer.AgentToolBudgetPort;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MandatoryWorkflowRecoveryCoordinatorTest {

    @Test
    void projectsSemanticAdmissionForDiscoveryCompletedBeforeRecovery() {
        MandatoryWorkflowRecoveryCoordinator coordinator = new MandatoryWorkflowRecoveryCoordinator(
            new AgentToolNameResolver(),
            mock(AgentToolArgumentResolver.class),
            mock(AgentWorkflowToolResolver.class),
            mock(AgentWorkflowStatePort.class),
            mock(MandatoryWorkflowTopology.class),
            mock(MandatoryWorkflowRecoveryPolicy.class),
            mock(MandatoryWorkflowResultReviewer.class),
            mock(ModelAssistedRetrievalBridge.class),
            mock(AgentToolBudgetPort.class),
            mock(AgentRunStore.class),
            new ObjectMapper()
        );
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_customer_service_template_query")
            .success(true)
            .input(Map.of("keywords", List.of("交易流水", "成交", "委托")))
            .output("""
                {"templates":[
                  {"templateId":"trade-flow","executorTool":"mcp_chatchat_mcp_server_api_template_execute"},
                  {"templateId":"orders","executorTool":"mcp_chatchat_mcp_server_api_template_execute"}
                ]}
                """)
            .durationMs(12L)
            .build();
        Map<String, Object> projectedOutput = Map.of(
            "templates", List.of(
                Map.of("templateId", "trade-flow",
                    "executorTool", "mcp_chatchat_mcp_server_api_template_execute")),
            "runtimeTemplateSelection", Map.of(
                "selectedTemplateIds", List.of("trade-flow"),
                "candidateCount", 2,
                "selectedCount", 1)
        );
        Map<String, InteractionToolTrace> reviewed = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<String> observations = new ArrayList<>();
        MandatoryWorkflowRecoveryCoordinator.Request request = request(metadata, observations);

        boolean satisfied = coordinator.reviewCompletedDiscoveryPredecessors(
            request, List.of(discovery), reviewed,
            (tool, input, output) -> new MandatoryWorkflowRecoveryCoordinator.SemanticReview(
                true, true, "one template admitted", projectedOutput,
                Map.of("selectedTemplateIds", List.of("trade-flow"))));

        assertThat(satisfied).isTrue();
        assertThat(reviewed).containsKey(discovery.getToolName());
        assertThat(reviewed.get(discovery.getToolName()).getOutput())
            .contains("runtimeTemplateSelection", "trade-flow")
            .doesNotContain("orders");
        assertThat(reviewed.get(discovery.getToolName()).getRuntimeMetadata())
            .containsEntry("semanticCandidateReviewSatisfied", true);
        assertThat(metadata).containsKey("mandatorySemanticCandidateReviews");
        assertThat(observations).singleElement()
            .asString().contains("reused completed discovery");
    }

    @Test
    void keepsCompletedDiscoveryFailClosedWhenSemanticAdmissionRejectsIt() {
        MandatoryWorkflowRecoveryCoordinator coordinator = new MandatoryWorkflowRecoveryCoordinator(
            new AgentToolNameResolver(), mock(AgentToolArgumentResolver.class),
            mock(AgentWorkflowToolResolver.class), mock(AgentWorkflowStatePort.class),
            mock(MandatoryWorkflowTopology.class), mock(MandatoryWorkflowRecoveryPolicy.class),
            mock(MandatoryWorkflowResultReviewer.class), mock(ModelAssistedRetrievalBridge.class),
            mock(AgentToolBudgetPort.class), mock(AgentRunStore.class), new ObjectMapper());
        InteractionToolTrace discovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_customer_service_template_query")
            .success(true).input(Map.of()).output("{\"templates\":[]}").build();
        Map<String, Object> metadata = new LinkedHashMap<>();

        boolean satisfied = coordinator.reviewCompletedDiscoveryPredecessors(
            request(metadata, new ArrayList<>()), List.of(discovery), new LinkedHashMap<>(),
            (tool, input, output) -> new MandatoryWorkflowRecoveryCoordinator.SemanticReview(
                true, false, "no candidate matches", output.getData(), Map.of()));

        assertThat(satisfied).isFalse();
        assertThat(metadata)
            .containsEntry("mandatoryWorkflowSemanticReviewBlocked", true)
            .containsEntry("mandatoryWorkflowSemanticReviewReason", "no candidate matches")
            .containsEntry("mandatoryWorkflowStoppedOnFailure", discovery.getToolName());
    }

    private MandatoryWorkflowRecoveryCoordinator.Request request(
        Map<String, Object> metadata, List<String> observations) {
        return new MandatoryWorkflowRecoveryCoordinator.Request(
            null, new ArrayList<>(), observations, "分析交易偏好", "conversation", "request",
            "user", "tenant", List.of(), List.of(), List.of(), List.of(), 5,
            metadata, Map.of(), 20, "", () -> false);
    }
}
