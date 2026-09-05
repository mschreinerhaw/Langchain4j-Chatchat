package com.chatchat.agents.orchestration.analysis.nodes.merge;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.orchestration.analysis.model.DatasetRelationshipPlan;
import com.chatchat.agents.orchestration.analysis.nodes.analysis.AnalysisNodeProtocol;
import com.chatchat.agents.orchestration.analysis.dispatch.DatasetAnalysisNode;



import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisParticipant;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisScope;
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

class StructuredFindingMergerTest {

    private final GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");
    private final AnalysisNodeProtocol bridge = new AnalysisNodeProtocol();

    @Test
    void workerAndDriverImplementTheSameRoleNeutralAnalysisParticipant() {
        assertThat(DataAnalysisParticipant.class).isAssignableFrom(DatasetAnalysisNode.class);
        assertThat(DataAnalysisParticipant.class).isAssignableFrom(StructuredFindingMerger.class);

        DatasetRelationshipPlan plan = DatasetRelationshipPlan.create(List.of(
            dataset("orders", Map.of())));
        StructuredFindingMerger.Context context = new StructuredFindingMerger.Context(
            prompt -> "unused", scope, plan, "analyze trading preference");
        StructuredFindingMerger.Request request = StructuredFindingMerger.Request.create(
            context, List.of(chunk("orders", "orders evidence")));

        assertThat(request.assignment().scope())
            .isEqualTo(DataAnalysisScope.ASSIGNED_DATASET_COLLECTION);
        assertThat(request.assignment().inputReferences())
            .containsExactlyElementsOf(request.summaries().stream()
                .map(AnalysisSummaryResult::resultId).distinct().toList());
    }

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

        StructuredFindingMerger.Result result = new StructuredFindingMerger().reduce(
            model::chat, scope, plan, chunks, "analyze portfolio activity");

        assertThat(result.datasetSummaries()).hasSize(3)
            .allSatisfy(summary -> {
                assertThat(summary.scope()).isEqualTo("DATASET_SYNTHESIS");
                assertThat(summary.evidence())
                    .containsEntry("analysisDecisionOperatingModelVersion",
                        "data_analysis_decision_operating_model.v1")
                    .containsEntry("analysisParticipantRole", "REDUCER")
                    .containsEntry("managementReviewInput", true);
            });
        assertThat(result.relationshipGroupSummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.scope()).isEqualTo("RELATIONSHIP_GROUP_SYNTHESIS");
            assertThat(summary.content()).contains("assets evidence", "positions evidence").doesNotContain("orders evidence");
            assertThat(summary.inputSummaryResultIds()).hasSize(2);
            assertThat(summary.evidence())
                .containsEntry("analysisDecisionOperatingModelVersion",
                    "data_analysis_decision_operating_model.v1")
                .containsEntry("analysisParticipantRole", "REDUCER")
                .containsEntry("managementReviewInput", true);
        });
        assertThat(result.finalInputs()).hasSize(2)
            .extracting(AnalysisSummaryResult::scope)
            .containsExactly("RELATIONSHIP_GROUP_SYNTHESIS", "DATASET_SYNTHESIS");
        assertThat(result.promptEvidence())
            .contains("dataset_relationship_plan.v1", "hierarchical_analysis_reduce.v1")
            .contains("assets evidence", "positions evidence", "orders evidence");
        org.mockito.Mockito.verifyNoInteractions(model);
    }

    @Test
    void acceptsWorkerReducedDatasetWithoutReducingItsChunksAgain() {
        StructuredFindingMerger reducer = new StructuredFindingMerger();
        AnalysisSummaryResult workerResult = reducer.reduceDataset(prompt -> {
            throw new AssertionError("single chunk does not require model reduction");
        }, scope, "assets", List.of(chunk("assets", "worker dataset result")),
            "analyze portfolio activity");
        AtomicInteger driverModelCalls = new AtomicInteger();

        StructuredFindingMerger.Result result = reducer.reduce(prompt -> {
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
            AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, AgentRoleAnalysisContext.create(
                "Activity analyst", "Analyze activity quality",
                List.of("Daily review"), List.of("quality")),
            "templateMatchAnalysis", Map.of("selectedTemplateIds", List.of("orders")),
            "workerAnalysisContext", Map.of(
                "schemaVersion", "worker_analysis_context.v2",
                "originalUserQuestion", "analyze trading preference",
                "currentTemplate", Map.of("templateId", "orders")));
        AnalysisSummaryResult workerResult = AnalysisSummaryResult.chunk(scope,
            Map.of("datasetReference", "orders", "chunkIndex", 1), context,
            "orders analysis", "MODEL_SUMMARY");

        StructuredFindingMerger.Result result = new StructuredFindingMerger().reduce(
            prompt -> "unused", scope,
            DatasetRelationshipPlan.create(List.of(new DatasetRelationshipPlan.Dataset("orders", context))),
            List.of(workerResult), "analyze trading preference");

        assertThat(result.promptEvidence())
            .contains("templateMatchAnalysis", "workerAnalysisContext",
                AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY)
            .contains("Analyze activity quality", "Daily review", "quality")
            .contains("analyze trading preference", "orders analysis");
    }

    @Test
    void preservesCommonAgentRoleContextAcrossRelationshipGroupReduction() {
        Map<String, Object> role = AgentRoleAnalysisContext.create(
            "Reliability analyst", "Analyze service reliability",
            List.of("Incident review"), List.of("reliability"));
        Map<String, Object> leftContext = bridge.govern("left", Map.of(
            AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, role,
            "relationships", Map.of("targetDataset", "right")),
            List.of(Map.of("VALUE", 1)));
        Map<String, Object> rightContext = bridge.govern("right", Map.of(
            AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, role), List.of(Map.of("VALUE", 2)));
        List<AnalysisSummaryResult> inputs = List.of(
            bridge.preserve(scope, bridge.position("left", 1, 1, 1, 1, 1), leftContext,
                List.of(Map.of("VALUE", 1))),
            bridge.preserve(scope, bridge.position("right", 1, 1, 1, 1, 1), rightContext,
                List.of(Map.of("VALUE", 2))));
        AtomicReference<String> prompt = new AtomicReference<>();

        StructuredFindingMerger.Result result = new StructuredFindingMerger().reduce(value -> {
            prompt.set(value);
            return "combined analysis";
        }, scope, DatasetRelationshipPlan.create(List.of(
            new DatasetRelationshipPlan.Dataset("left", leftContext),
            new DatasetRelationshipPlan.Dataset("right", rightContext))), inputs,
            "analyze returned service data");

        assertThat(prompt.get()).isNull();
        assertThat(result.relationshipGroupSummaries()).singleElement().satisfies(summary ->
            assertThat(summary.analysisContext())
                .containsEntry(AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, role));
        assertThat(result.promptEvidence()).contains(
            AgentRoleAnalysisContext.ANALYSIS_CONTEXT_KEY, "Analyze service reliability");
    }

    @Test
    void workerDatasetReductionCarriesTheCompleteOriginalUserQuestionAndMetadata() {
        String originalQuestion = "请结合全部持仓记录分析客户风险偏好，并说明结论依据。";
        AtomicReference<String> reductionPrompt = new AtomicReference<>();
        AnalysisSummaryResult first = chunk("assets", "first partition");
        AnalysisSummaryResult second = chunk("assets", "second partition");

        AnalysisSummaryResult result = new StructuredFindingMerger().reduceDataset(prompt -> {
            reductionPrompt.set(prompt);
            return "dataset result grounded in both partitions";
        }, scope, "assets", List.of(first, second), originalQuestion);

        assertThat(result.scope()).isEqualTo("DATASET_SYNTHESIS");
        assertThat(reductionPrompt.get()).isNull();
        assertThat(result.content()).contains("first partition", "second partition");
        assertThat(result.inputSummaryResultIds()).isNotEmpty();
    }

    @Test
    void reducerPublishesOnlyLineageBoundDerivedArtifacts() {
        AnalysisSummaryResult first = artifactChunk(
            "metrics", "claim:waits", "Allocation waits are 0", "0");
        AnalysisSummaryResult second = artifactChunk(
            "metrics", "claim:pending", "Pending writes are 0", "0");

        AnalysisSummaryResult result = new StructuredFindingMerger().reduceDataset(prompt -> """
            {"schemaVersion":"analysis_reducer_report.v1",
             "summary":"No current allocation or pending-write pressure is visible.",
             "derivedClaims":[
               {"claimId":"reducer:buffer-pressure",
                "text":"No current allocation or pending-write pressure is visible.",
                "basisClaimIds":["claim:waits","claim:pending"],
                "status":"SUPPORTED","confidence":"HIGH",
                "significance":"Combines related current-state signals","caveats":[]},
               {"claimId":"reducer:unbound","text":"Unbound conclusion",
                "basisClaimIds":["claim:missing"],"status":"SUPPORTED",
                "confidence":"HIGH","significance":"invalid","caveats":[]}]}
            """, scope, "metrics", List.of(first, second), "analyze current engine health");

        assertThat(result.content()).contains("Allocation waits are 0", "Pending writes are 0");
        assertThat(result.evidence().get("analysisArtifacts").toString())
            .contains("claim:waits", "claim:pending").doesNotContain("reducer:buffer-pressure", "reducer:unbound");
    }

    private AnalysisSummaryResult artifactChunk(String dataset, String claimId,
                                                String claim, String value) {
        return AnalysisSummaryResult.chunk(scope,
            Map.of("datasetReference", dataset, "chunkIndex", 1), Map.of(), claim,
            "MODEL_SUMMARY", Map.of("analysisArtifacts", List.of(Map.ofEntries(
                Map.entry("schemaVersion", "analysis_artifact.v1"),
                Map.entry("artifactId", claimId),
                Map.entry("artifactType", "BUSINESS_CLAIM"),
                Map.entry("sourceStage", "WORKER"),
                Map.entry("sourceScope", dataset),
                Map.entry("claimClass", "OBSERVED_RETURNED_FACT"),
                Map.entry("text", claim),
                Map.entry("status", "SUPPORTED"),
                Map.entry("recordRefs", List.of(dataset + ".records[1]")),
                Map.entry("supportingValues", List.of(value)),
                Map.entry("basisClaimIds", List.of()),
                Map.entry("caveats", List.of()),
                Map.entry("reviewReasons", List.of())))));
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
