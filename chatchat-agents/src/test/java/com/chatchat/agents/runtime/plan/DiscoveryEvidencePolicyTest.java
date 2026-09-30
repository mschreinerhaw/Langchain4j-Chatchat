package com.chatchat.agents.runtime.plan;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class DiscoveryEvidencePolicyTest {
    @Test void discoveryOnlyIsSupplementalButDataDocumentAndUnknownRemainStrict() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole("opaque_a")).thenReturn(ToolWorkflowRole.ASSET_DISCOVERY);
        when(registry.getWorkflowRole("opaque_b")).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole("opaque_c")).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        var plan = plan("opaque_a", "opaque_b");
        assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of("opaque_a", "opaque_b"), Map.of(), registry)).isTrue();
        assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of("opaque_a", "opaque_b"), Map.of("workflowFamily", "ASSET_GUIDANCE"), registry)).isTrue();
        for (String family : List.of("DATA_ANALYSIS", "DOCUMENT", "ACTION", "DIRECT_ANSWER"))
            assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of("opaque_a", "opaque_b"), Map.of("workflowFamily", family), registry)).isFalse();
        assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of("opaque_a", "opaque_b", "opaque_c"), Map.of(), registry)).isFalse();
        assertThat(DiscoveryEvidencePolicy.supplemental(plan("opaque_a", "opaque_c"), List.of("opaque_a"), Map.of(), registry)).isFalse();
        assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of("unknown"), Map.of(), registry)).isFalse();
        assertThat(DiscoveryEvidencePolicy.supplemental(plan, List.of(), Map.of(), registry)).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "false,true", "true,false"})
    void noMatchIsOptionalOnlyInDiscoveryWorkflow(boolean supplemental, boolean transportSuccess) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(java.util.Set.of("opaque_a", "opaque_b"));
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenReturn(com.chatchat.common.tool.ToolMetadata.builder().riskLevel("low").build());
        when(registry.getWorkflowRole(any())).thenReturn(ToolWorkflowRole.ASSET_DISCOVERY);
        var tools = mock(com.chatchat.agents.runtime.tool.ToolRuntimeService.class);
        var output = transportSuccess ? com.chatchat.common.tool.ToolOutput.success(Map.of("schemaVersion", "asset_query_result.v1",
            "success", true, "returnedCount", 1, "assets", List.of(Map.of("asset", Map.of("id", "candidate")))))
            : com.chatchat.common.tool.ToolOutput.failure("upstream unavailable");
        when(tools.execute(any())).thenReturn(new com.chatchat.agents.runtime.tool.ToolRuntimeExecution(output,
            com.chatchat.common.tool.ToolMetadata.builder().id("opaque_a").build(), null, "result", Map.of()));
        var runtime = new InterpretationPlanRuntime(tools, new InterpretationPlanValidator(), new InterpretationPlanOptimizer(registry), null,
            review -> InterpretationPlanRuntime.StepReview.rejected("no relevant candidate",
                Map.of("selectedAssetIds", List.of(), "rejectedAssetIds", List.of("candidate"))), null);
        var result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(plan("opaque_a", "opaque_b"), registry,
            List.of("opaque_a", "opaque_b"), "tenant", "request", "conversation", "user",
            Map.of(DiscoveryEvidencePolicy.ATTRIBUTE, supplemental)));
        if (supplemental && transportSuccess) {
            assertThat(result.steps().get(0).success()).isTrue();
            assertThat(result.steps().get(0).metadata()).containsEntry("discoveryMatchStatus", "NO_MATCH");
            assertThat(result.success()).as("%s: %s", result.status(), result.errorMessage()).isTrue();
            var observations = new java.util.ArrayList<String>();
            var reviewed = new com.chatchat.agents.orchestration.planning.execution.PlanExecutionResultCoordinator()
                .review("initial", result, observations, new java.util.LinkedHashMap<>());
            assertThat(reviewed.success()).as("result-review barrier: %s", observations).isTrue();
            assertThat(observations).noneMatch(value -> value.contains("rewrite"));
        } else assertThat(result.steps().get(0).success()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void supplementalTemplatePageDoesNotRequestRewrite(boolean selected) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(java.util.Set.of("opaque_a", "opaque_b"));
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenReturn(com.chatchat.common.tool.ToolMetadata.builder().riskLevel("low").build());
        when(registry.getWorkflowRole(any())).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        var tools = mock(com.chatchat.agents.runtime.tool.ToolRuntimeService.class);
        var output = com.chatchat.common.tool.ToolOutput.success(Map.of("hasMore", true,
            "nextCursor", "opaque-next", "templates", List.of(Map.of("templateId", "candidate"))));
        when(tools.execute(any())).thenReturn(new com.chatchat.agents.runtime.tool.ToolRuntimeExecution(output,
            com.chatchat.common.tool.ToolMetadata.builder().id("opaque_a").build(), null, "result", Map.of()));
        var runtime = new InterpretationPlanRuntime(tools, new InterpretationPlanValidator(), new InterpretationPlanOptimizer(registry), null,
            review -> InterpretationPlanRuntime.StepReview.rejected("supplementary coverage incomplete",
                Map.of("originalUserQuestion", "explain asset usage", "selectedTemplateIds", selected ? List.of("candidate") : List.of(),
                    "rejectedTemplateIds", selected ? List.of() : List.of("candidate"),
                    "coverageDecision", "NEED_NEXT_PAGE", "retrievalOutcome", "PAGE_EXHAUSTED_HAS_MORE")), null);
        var result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(plan("opaque_a", "opaque_b"), registry,
            List.of("opaque_a", "opaque_b"), "tenant", "request", "conversation", "user",
            Map.of(DiscoveryEvidencePolicy.ATTRIBUTE, true)));
        assertThat(result.steps().get(0).success()).isTrue();
        assertThat(result.steps().get(0).metadata()).containsEntry("templateDiscoveryContinuationRequired", false);
        assertThat(result.steps().get(0).metadata().get("runtimeSelectedTemplateIds"))
            .isEqualTo(selected ? List.of("candidate") : List.of());
        assertThat(result.success()).as("%s: %s", result.status(), result.errorMessage()).isTrue();
            var observations = new java.util.ArrayList<String>();
            var reviewed = new com.chatchat.agents.orchestration.planning.execution.PlanExecutionResultCoordinator()
                .review("initial", result, observations, new java.util.LinkedHashMap<>());
            assertThat(reviewed.success()).as("result-review barrier: %s", observations).isTrue();
            assertThat(observations).noneMatch(value -> value.contains("rewrite"));
    }

    @Test void supplementaryEvidenceDoesNotRemoveRequiredCallsOrAllowInventedFacts() {
        var original = new com.chatchat.agents.assessment.TaskContract(null, "mixed", "explain",
            com.chatchat.agents.assessment.TaskContract.EvidenceRequirement.REQUIRED, false, "answer",
            List.of("opaque_a"), List.of(new com.chatchat.agents.assessment.TaskContract.EvidenceItem(
                "e1", 1, "opaque_a", com.chatchat.agents.assessment.TaskContract.EvidenceImportance.REQUIRED)));
        var result = DiscoveryEvidencePolicy.supplementalContract(original);
        assertThat(result.evidenceRequirement()).isEqualTo(com.chatchat.agents.assessment.TaskContract.EvidenceRequirement.OPTIONAL);
        assertThat(result.evidenceItems()).allSatisfy(item -> assertThat(item.importance())
            .isEqualTo(com.chatchat.agents.assessment.TaskContract.EvidenceImportance.OPTIONAL));
        assertThat(result.mandatoryTools()).containsExactly("opaque_a");
        assertThat(result.allowAssumptions()).isFalse();
        assertThat(original.evidenceRequirement()).isEqualTo(com.chatchat.agents.assessment.TaskContract.EvidenceRequirement.REQUIRED);
    }

    static InterpretationPlan plan(String first, String second) {
        return new InterpretationPlan("1.0", new InterpretationPlan.Intent("explanation", "explain", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", first, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", second, Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "explanation"), List.of(2), null, null)),
                List.of(), List.of(), List.of(), null),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(first, second), List.of(), 30000),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of()));
    }
}
