package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationPlanOptimizerTest {

    @Test
    void userDefinedTaskWorkflowOverridesModelToolEdges() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("workflow", "run configured workflow", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "load_asset", Map.of(), List.of(3), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "select_template", Map.of(), List.of(3), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "publish_result", Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of("load_asset", "select_template", "publish_result"), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );
        List<Map<String, Object>> configuredDag = List.of(
            Map.of("tool", "load_asset", "dependsOnTools", List.of()),
            Map.of("tool", "select_template", "dependsOnTools", List.of("load_asset")),
            Map.of("tool", "publish_result", "dependsOnTools", List.of("select_template"))
        );

        InterpretationPlanOptimizer.OptimizationResult result =
            new InterpretationPlanOptimizer().optimize(plan, configuredDag);

        InterpretationPlan.Step load = stepByTool(result.plan(), "load_asset");
        InterpretationPlan.Step select = stepByTool(result.plan(), "select_template");
        InterpretationPlan.Step publish = stepByTool(result.plan(), "publish_result");
        assertThat(result.appliedPasses()).contains("AuthoritativeWorkflowDagPass");
        assertThat(load.dependsOn()).isEmpty();
        assertThat(select.dependsOn()).containsExactly(load.id());
        assertThat(publish.dependsOn()).containsExactly(select.id());
    }

    @Test
    void repairsMisorderedTemplateExecutionChainAndRemovesModelOwnedTemplateIds() {
        String assetTool = "mcp_chatchat_mcp_server_api_asset_query";
        String templateTool = "mcp_chatchat_mcp_server_api_template_query";
        String executeTool = "mcp_chatchat_mcp_server_api_template_execute";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_execution", "Execute the selected API template", "medium"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(20, "mcp_tool", executeTool,
                    Map.of("templateId", "model-invented-id", "parameters", Map.of("query", "x")),
                    List.of(), null, null),
                new InterpretationPlan.Step(30, "mcp_tool", templateTool,
                    Map.of("templateIds", List.of("model-invented-id")), List.of(), null, null),
                new InterpretationPlan.Step(10, "mcp_tool", assetTool,
                    Map.of("query", "x"), List.of(), null, null),
                new InterpretationPlan.Step(40, "final_answer", "", Map.of("answer", "done"),
                    List.of(20), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of(assetTool, templateTool, executeTool), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        InterpretationPlan.Step asset = stepByTool(result.plan(), assetTool);
        InterpretationPlan.Step template = stepByTool(result.plan(), templateTool);
        InterpretationPlan.Step execute = stepByTool(result.plan(), executeTool);
        assertThat(result.appliedPasses()).contains("TemplateExecutionDagRepairPass");
        assertThat(template.dependsOn()).contains(asset.id());
        assertThat(execute.dependsOn()).contains(template.id());
        assertThat(template.input()).doesNotContainKeys("templateIds", "template_ids");
        assertThat(execute.input()).doesNotContainKeys(
            "templateId", "template_id", "template", "runtimeTemplateBinding");
        assertThat(result.plan().plan().bindings()).anySatisfy(binding -> {
            assertThat(binding.from()).isEqualTo(template.id());
            assertThat(binding.to()).isEqualTo(execute.id());
            assertThat(binding.outputPath()).isEqualTo("$.templates[0].templateId");
            assertThat(binding.inputField()).isEqualTo("$.templateId");
            assertThat(binding.required()).isTrue();
        });
        assertThat(result.plan().plan().dependencyContracts())
            .anySatisfy(contract -> {
                assertThat(contract.from()).isEqualTo(asset.id());
                assertThat(contract.to()).isEqualTo(template.id());
                assertThat(contract.required()).isTrue();
            })
            .anySatisfy(contract -> {
                assertThat(contract.from()).isEqualTo(template.id());
                assertThat(contract.to()).isEqualTo(execute.id());
                assertThat(contract.required()).isTrue();
            });
    }

    private InterpretationPlan.Step stepByTool(InterpretationPlan plan, String toolName) {
        return plan.steps().stream()
            .filter(step -> toolName.equals(step.toolName()))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void prunesNoopStepsDedupesToolCallsAndEnablesParallelHint() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Optimize this plan", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "reasoning", "", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "x"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "web_search", Map.of("query", "x"), List.of(1), null, null),
                new InterpretationPlan.Step(4, "mcp_tool", "document_search", Map.of("query", "y"), List.of(), null, null),
                new InterpretationPlan.Step(5, "final_answer", "", Map.of("answer", "done"), List.of(2, 3, 4), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(5, null, List.of("web_search", "document_search"), List.of(), 30000),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses())
            .contains("PruneNoopPass", "DedupeToolCallPass", "ParallelHintPass");
        assertThat(result.plan().steps()).hasSize(3);
        assertThat(result.plan().executionPolicy().allowParallel()).isTrue();
        assertThat(result.plan().steps()).extracting(InterpretationPlan.Step::id)
            .containsExactly(1, 2, 3);
        assertThat(result.plan().steps().get(2).dependsOn()).containsExactly(1, 2);
    }

    @Test
    void stabilityGuardPreventsPruneAndDedupeWhilePolicyOrderingStillApplies() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Respect stability", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "reasoning", "", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "x"), List.of(), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "web_search", Map.of("query", "x"), List.of(), null, null),
                    new InterpretationPlan.Step(4, "mcp_tool", "document_search", Map.of("query", "y"), List.of(), null, null),
                    new InterpretationPlan.Step(5, "final_answer", "", Map.of("answer", "done"), List.of(2, 3, 4), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(2, 5, "data.results", "array", false)),
                new InterpretationPlan.Stability(List.of(1), List.of("web_search"), true, List.of("reasoning"))
            ),
            new InterpretationPlan.ExecutionPolicy(
                5,
                null,
                List.of("web_search", "document_search"),
                List.of(),
                30000,
                1,
                "safe_answer",
                Map.of("document_search", 0.9, "web_search", 0.2),
                100.0,
                30000,
                0.7
            ),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses())
            .doesNotContain("PruneNoopPass", "DedupeToolCallPass")
            .contains("PolicyAwareOrderingPass", "ParallelHintPass");
        assertThat(result.plan().steps()).hasSize(5);
        assertThat(result.plan().steps().get(0).actionType()).isEqualTo("reasoning");
        assertThat(result.plan().steps().get(1).toolName()).isEqualTo("document_search");
        assertThat(result.plan().plan().stability().stableNodes()).contains(1);
        assertThat(result.plan().plan().edgeContracts()).singleElement()
            .satisfies(contract -> assertThat(contract.to()).isNotNull());
    }

    @Test
    void documentRetrievalPlanRemovesNonStrictDocumentIdsAndRelaxesExecutionPolicy() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Explain a document", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "mcp_chatchat_mcp_server_document_search",
                    Map.of(
                        "query", "跨交易日 任务依赖 执行判断 调度方案",
                        "document_ids", List.of("20260617_c489d851"),
                        "selectedDocumentIds", List.of("20260617_c489d851"),
                        "documentVisibilityEnforced", true,
                        "tags", List.of("agent-bound")
                    ),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_document_search"),
                List.of(),
                30000,
                1,
                "safe_answer"
            ),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.9, 0.2, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses())
            .contains("DocumentSearchInputSanitizerPass", "RetrievalPolicyGuardPass");
        assertThat(result.plan().steps().get(0).input())
            .containsEntry("query", "跨交易日 任务依赖 执行判断 调度方案")
            .doesNotContainKey("document_ids")
            .doesNotContainKey("selectedDocumentIds")
            .doesNotContainKey("documentVisibilityEnforced")
            .doesNotContainKey("tags");
        assertThat(result.plan().executionPolicy().maxSteps()).isEqualTo(4);
        assertThat(result.plan().executionPolicy().maxRewriteTimes()).isEqualTo(2);
    }

    @Test
    void documentRetrievalPlanKeepsStrictDocumentIds() {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Explain a scoped document", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "document_search",
                    Map.of(
                        "query", "跨交易日任务依赖执行判断与调度方案",
                        "document_ids", List.of("20260617_c489d851"),
                        "selectedDocumentIds", List.of("20260617_c489d851"),
                        "documentVisibilityEnforced", true,
                        "tags", List.of("agent-bound"),
                        "strict_document_scope", true
                    ),
                    List.of(),
                    null,
                    null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(4, false, List.of("document_search"), List.of(), 30000, 2, "safe_answer"),
            new InterpretationPlan.Review(new InterpretationPlan.SelfCheck(0.9, 0.2, true, List.of()), List.of())
        );

        InterpretationPlanOptimizer.OptimizationResult result = new InterpretationPlanOptimizer().optimize(plan);

        assertThat(result.appliedPasses()).doesNotContain("DocumentSearchInputSanitizerPass");
        assertThat(result.plan().steps().get(0).input())
            .containsEntry("document_ids", List.of("20260617_c489d851"))
            .containsEntry("selectedDocumentIds", List.of("20260617_c489d851"))
            .containsEntry("documentVisibilityEnforced", true)
            .containsEntry("tags", List.of("agent-bound"))
            .containsEntry("strict_document_scope", true);
    }
}

