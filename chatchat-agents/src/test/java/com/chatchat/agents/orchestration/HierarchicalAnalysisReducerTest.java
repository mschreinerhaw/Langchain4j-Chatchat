package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
            model, scope, plan, chunks, "analyze portfolio activity");

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
