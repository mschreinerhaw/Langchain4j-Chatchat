package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.analysis.summary.AnalysisSummaryGovernanceBridge;
import com.chatchat.agents.orchestration.analysis.summary.HierarchicalAnalysisReducer;



import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalAnalysisReducerTest {

    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");
    private final AnalysisSummaryGovernanceBridge bridge = new AnalysisSummaryGovernanceBridge();

    @Test
    void groupsOnlyDatasetsConnectedByExplicitRelationships() {
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            dataset("assets", Map.of("relatedDatasets", List.of("positions"))),
            dataset("positions", Map.of()),
            dataset("orders", Map.of())
        ));

        assertThat(plan.schemaVersion()).isEqualTo("dataset_relationship_plan.v1");
        assertThat(plan.groups()).anySatisfy(group -> {
            assertThat(group.explicitRelationship()).isTrue();
            assertThat(group.datasetReferences()).containsExactly("assets", "positions");
        }).anySatisfy(group -> {
            assertThat(group.explicitRelationship()).isFalse();
            assertThat(group.datasetReferences()).containsExactly("orders");
        });
        assertThat(plan.edges()).hasSize(1);
        assertThat(plan.unresolvedReferences()).isEmpty();
    }

    @Test
    void keepsUnknownRelationshipStandaloneAndReportsIt() {
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            dataset("assets", Map.of("targetDataset", "missing-dataset")),
            dataset("orders", Map.of())
        ));

        assertThat(plan.groups()).hasSize(2).allSatisfy(group ->
            assertThat(group.explicitRelationship()).isFalse());
        assertThat(plan.unresolvedReferences()).singleElement()
            .asString().contains("assets -> missing-dataset");
    }

    @Test
    void resolvesTemplateMatchRelationshipsThroughWorkerTemplateIdentity() {
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            new DatasetRelationshipPlan.Dataset("asset-records", Map.of(
                "workerAnalysisContext", Map.of(
                    "currentTemplate", Map.of("templateId", "customer_asset")),
                "relationships", List.of(Map.of(
                    "fromTemplateId", "customer_asset",
                    "toTemplateId", "fund_flow",
                    "relationType", "CAUSE_VALIDATION")))),
            new DatasetRelationshipPlan.Dataset("flow-records", Map.of(
                "workerAnalysisContext", Map.of(
                    "currentTemplate", Map.of("templateId", "fund_flow"))))
        ));

        assertThat(plan.groups()).singleElement().satisfies(group ->
            assertThat(group.datasetReferences()).containsExactly("asset-records", "flow-records"));
        assertThat(plan.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.fromDataset()).isEqualTo("asset-records");
            assertThat(edge.toDataset()).isEqualTo("flow-records");
        });
        assertThat(plan.unresolvedReferences()).isEmpty();
    }

    @Test
    void reducesExplicitGroupAndKeepsStandaloneDatasetAsSeparateFinalInput() {
        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            dataset("assets", Map.of("targetDataset", "positions")),
            dataset("positions", Map.of()),
            dataset("orders", Map.of())
        ));
        List<AnalysisSummaryResult> chunks = List.of(
            chunk("assets", "assets evidence"),
            chunk("positions", "positions evidence"),
            chunk("orders", "orders evidence")
        );
        ChatModel model = mock(ChatModel.class);
        when(model.chat(argThat((String prompt) -> prompt.contains("RELATIONSHIP_GROUP_SYNTHESIS")
            && prompt.contains("assets") && prompt.contains("positions")
            && !prompt.contains("orders evidence"))))
            .thenReturn("assets and positions combined analysis");

        HierarchicalAnalysisReducer.Result result = new HierarchicalAnalysisReducer().reduce(
            model::chat, scope, plan, chunks, "analyze portfolio activity");

        assertThat(result.datasetSummaries()).hasSize(3)
            .allSatisfy(summary -> assertThat(summary.scope()).isEqualTo("DATASET_SYNTHESIS"));
        assertThat(result.relationshipGroupSummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.scope()).isEqualTo("RELATIONSHIP_GROUP_SYNTHESIS");
            assertThat(summary.content()).isEqualTo("assets and positions combined analysis");
            assertThat(summary.inputSummaryResultIds()).hasSize(2);
        });
        assertThat(result.finalInputs()).hasSize(2)
            .extracting(AnalysisSummaryResult::scope)
            .containsExactly("RELATIONSHIP_GROUP_SYNTHESIS", "DATASET_SYNTHESIS");
        assertThat(result.promptEvidence())
            .contains("dataset_relationship_plan.v1", "hierarchical_analysis_reduce.v1")
            .contains("assets and positions combined analysis", "orders evidence");
        verify(model).chat(argThat((String prompt) -> prompt.contains("authorizedRelationships")));
    }

    @Test
    void acceptsWorkerReducedDatasetWithoutReducingItsChunksAgain() {
        HierarchicalAnalysisReducer reducer = new HierarchicalAnalysisReducer();
        AnalysisSummaryResult workerResult = reducer.reduceDataset(prompt -> {
            throw new AssertionError("single chunk does not require model reduction");
        }, scope, "assets", List.of(chunk("assets", "worker dataset result")),
            "analyze portfolio activity");
        AtomicInteger driverModelCalls = new AtomicInteger();

        HierarchicalAnalysisReducer.Result result = reducer.reduce(prompt -> {
            driverModelCalls.incrementAndGet();
            return "unexpected duplicate reduction";
        }, scope, DatasetRelationshipPlan.create(List.of(dataset("assets", Map.of()))),
            List.of(workerResult), "analyze portfolio activity");

        assertThat(driverModelCalls).hasValue(0);
        assertThat(result.datasetSummaries()).containsExactly(workerResult);
        assertThat(result.finalInputs()).containsExactly(workerResult);
    }

    @Test
    void preservesTemplateAnalysisContractsInFinalPromptEvidence() {
        Map<String, Object> context = Map.of(
            "templateMatchAnalysis", Map.of("selectedTemplateIds", List.of("orders")),
            "workerAnalysisContext", Map.of(
                "schemaVersion", "worker_analysis_context.v2",
                "originalUserQuestion", "analyze trading preference",
                "currentTemplate", Map.of("templateId", "orders")));
        AnalysisSummaryResult workerResult = AnalysisSummaryResult.chunk(scope,
            Map.of("datasetReference", "orders", "chunkIndex", 1), context,
            "orders analysis", "MODEL_SUMMARY");

        HierarchicalAnalysisReducer.Result result = new HierarchicalAnalysisReducer().reduce(
            prompt -> "unused", scope,
            DatasetRelationshipPlan.create(List.of(new DatasetRelationshipPlan.Dataset("orders", context))),
            List.of(workerResult), "analyze trading preference");

        assertThat(result.promptEvidence())
            .contains("templateMatchAnalysis", "workerAnalysisContext")
            .contains("analyze trading preference", "orders analysis");
    }

    @Test
    void workerDatasetReductionCarriesTheCompleteOriginalUserQuestionAndMetadata() {
        String originalQuestion = "请结合全部持仓记录分析客户风险偏好，并说明结论依据。";
        AtomicReference<String> reductionPrompt = new AtomicReference<>();
        AnalysisSummaryResult first = chunk("assets", "first partition");
        AnalysisSummaryResult second = chunk("assets", "second partition");

        AnalysisSummaryResult result = new HierarchicalAnalysisReducer().reduceDataset(prompt -> {
            reductionPrompt.set(prompt);
            return "dataset result grounded in both partitions";
        }, scope, "assets", List.of(first, second), originalQuestion);

        assertThat(result.scope()).isEqualTo("DATASET_SYNTHESIS");
        assertThat(reductionPrompt.get())
            .contains("Original user question (authoritative analysis intent): " + originalQuestion)
            .contains("analysisContext", "assets", "first partition", "second partition")
            .contains("objectiveAlignment", "Never infer them from field names")
            .contains("do not aggregate, deduplicate, substitute, or generalize")
            .contains("Do not concatenate chunk summaries");
    }

    private DatasetRelationshipPlan.Dataset dataset(String reference, Object relationships) {
        return new DatasetRelationshipPlan.Dataset(reference, Map.of(
            "source", Map.of("toolName", reference),
            "relationships", relationships
        ));
    }

    private AnalysisSummaryResult chunk(String dataset, String content) {
        Map<String, Object> context = bridge.govern(dataset,
            Map.of("source", Map.of("toolName", dataset)), List.of(Map.of("VALUE", content)));
        return bridge.preserve(scope, bridge.position(dataset, 1, 1, 1, 1, 1),
            context, List.of(Map.of("VALUE", content)));
    }
}
