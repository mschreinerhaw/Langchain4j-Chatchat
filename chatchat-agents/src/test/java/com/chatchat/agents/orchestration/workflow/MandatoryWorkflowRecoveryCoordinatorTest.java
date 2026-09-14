package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.answer.AgentToolBudgetPort;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;
import com.chatchat.agents.orchestration.tool.AgentToolArgumentResolver;
import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MandatoryWorkflowRecoveryCoordinatorTest {

    @Test
    void missingInputsOnOneProviderDoNotSuppressAnIndependentProvider() {
        AgentToolArgumentResolver arguments = mock(AgentToolArgumentResolver.class);
        AgentWorkflowToolResolver workflow = mock(AgentWorkflowToolResolver.class);
        AgentWorkflowStatePort state = mock(AgentWorkflowStatePort.class);
        MandatoryWorkflowTopology topology = mock(MandatoryWorkflowTopology.class);
        MandatoryWorkflowRecoveryPolicy policy = mock(MandatoryWorkflowRecoveryPolicy.class);
        MandatoryWorkflowResultReviewer reviewer = mock(MandatoryWorkflowResultReviewer.class);
        ModelAssistedRetrievalBridge bridge = mock(ModelAssistedRetrievalBridge.class);
        AgentToolBudgetPort budget = mock(AgentToolBudgetPort.class);
        String incomplete = "incomplete_provider";
        String independent = "independent_provider";
        when(state.completedToolsFromTraces(any())).thenAnswer(invocation -> {
            List<InteractionToolTrace> traces = invocation.getArgument(0);
            java.util.Set<String> completed = new java.util.LinkedHashSet<>();
            traces.stream().filter(InteractionToolTrace::isSuccess)
                .map(InteractionToolTrace::getToolName).forEach(completed::add);
            return completed;
        });
        when(state.completedToolsFromEvents(any())).thenReturn(java.util.Set.of());
        when(state.attributesWithCompletedTools(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflow.missingMandatoryTools(any(), any(java.util.Set.class))).thenAnswer(invocation -> {
            List<String> required = invocation.getArgument(0);
            java.util.Set<String> completed = invocation.getArgument(1);
            return required.stream().filter(tool -> !completed.contains(tool)).toList();
        });
        when(topology.dependencyOrderedFallbackTools(any(), any(), any(), any()))
            .thenReturn(List.of(incomplete, independent));
        when(topology.unresolvedDependencies(any(), any(), anyString(), any())).thenReturn(List.of());
        when(topology.predecessorTraces(any(), any(), anyString(), any())).thenReturn(List.of());
        when(arguments.defaultToolArguments(anyString(), anyString(), anyInt())).thenReturn(Map.of());
        when(arguments.applyToolDefaults(anyString(), any(), any(), any(), anyString(), anyInt()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(arguments.applyDeterministicDependencyContracts(anyString(), any(), any(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(policy.missingRequiredInputs(eq(incomplete), any())).thenReturn(List.of("scope"));
        when(policy.missingRequiredInputs(eq(independent), any())).thenReturn(List.of());
        when(bridge.enrichWithGate(any(), anyString(), any(), any()))
            .thenAnswer(invocation -> new ModelAssistedRetrievalBridge.EnrichmentResult(
                invocation.getArgument(2), Map.of(), false));
        when(reviewer.reviewPredecessors(anyString(), any())).thenReturn(Map.of("satisfied", true));
        when(reviewer.review(eq(independent), any())).thenReturn(Map.of("satisfied", true));
        when(budget.markToolBudgetExceeded(anyString(), anyInt(), any(), any(), any())).thenReturn(false);
        MandatoryWorkflowRecoveryCoordinator coordinator = new MandatoryWorkflowRecoveryCoordinator(
            new AgentToolNameResolver(), arguments, workflow, state, topology, policy, reviewer,
            bridge, budget, mock(AgentRunStore.class), new ObjectMapper());
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<String> observations = new ArrayList<>();
        MandatoryWorkflowRecoveryCoordinator.Request request = new MandatoryWorkflowRecoveryCoordinator.Request(
            null, new ArrayList<>(), observations, "question", "conversation", "request", "user", "tenant",
            List.of(incomplete, independent), List.of(incomplete, independent), List.of(), List.of(), 5,
            metadata, Map.of(), 20, "", () -> false);

        coordinator.recover(request,
            (tool, input, conversation, requestId, user, tenant, tools, argumentsByTool, traces, attributes) -> {
                ToolOutput output = ToolOutput.success(Map.of("result", "ok"));
                return new AgentOrchestrator.ToolCallExecution(
                    InteractionToolTrace.builder().toolName(tool).success(true).build(),
                    tool + " completed", output);
            },
            (attributes, tool) -> Map.of(),
            (tool, input, output) -> new MandatoryWorkflowRecoveryCoordinator.SemanticReview(
                false, true, "not required", output.getData(), Map.of()));

        assertThat(request.traces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly(independent);
        assertThat(metadata).containsEntry("mandatoryWorkflowMissingRequiredInputs", List.of("scope"));
        assertThat(observations).anyMatch(value -> value.contains("required inputs"));
    }

    @Test
    void reusesCommittedSemanticReviewWithoutCallingModelReviewerAgain() {
        MandatoryWorkflowRecoveryCoordinator coordinator = new MandatoryWorkflowRecoveryCoordinator(
            new AgentToolNameResolver(), mock(AgentToolArgumentResolver.class),
            mock(AgentWorkflowToolResolver.class), mock(AgentWorkflowStatePort.class),
            mock(MandatoryWorkflowTopology.class), mock(MandatoryWorkflowRecoveryPolicy.class),
            mock(MandatoryWorkflowResultReviewer.class), mock(ModelAssistedRetrievalBridge.class),
            mock(AgentToolBudgetPort.class), mock(AgentRunStore.class), new ObjectMapper());
        InteractionToolTrace reviewedDiscovery = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_customer_service_template_query")
            .success(true)
            .input(Map.of("keywords", List.of("trade")))
            .output("{\"templates\":[{\"templateId\":\"trade-flow\"}]}")
            .runtimeMetadata(Map.of(
                "semanticCandidateReviewSatisfied", true,
                "selectedTemplateIds", List.of("trade-flow")))
            .build();
        Map<String, InteractionToolTrace> reused = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<String> observations = new ArrayList<>();

        boolean satisfied = coordinator.reviewCompletedDiscoveryPredecessors(
            request(metadata, observations), List.of(reviewedDiscovery), reused,
            (tool, input, output) -> {
                throw new AssertionError("committed semantic review must not invoke the model reviewer again");
            });

        assertThat(satisfied).isTrue();
        assertThat(reused).containsEntry(reviewedDiscovery.getToolName(), reviewedDiscovery);
        assertThat(metadata).containsEntry("mandatoryWorkflowCommittedSemanticReviewReused", true);
        assertThat(observations).singleElement().asString().contains("reused committed semantic candidate review");
    }

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
            .containsKey("mandatoryWorkflowDeferredProviders");
    }

    private MandatoryWorkflowRecoveryCoordinator.Request request(
        Map<String, Object> metadata, List<String> observations) {
        return new MandatoryWorkflowRecoveryCoordinator.Request(
            null, new ArrayList<>(), observations, "分析交易偏好", "conversation", "request",
            "user", "tenant", List.of(), List.of(), List.of(), List.of(), 5,
            metadata, Map.of(), 20, "", () -> false);
    }
}
