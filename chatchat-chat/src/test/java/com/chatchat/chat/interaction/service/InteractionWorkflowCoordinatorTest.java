package com.chatchat.chat.interaction.service;

import com.chatchat.chat.asset.AssetGuidanceInteractionBridge;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractionWorkflowCoordinatorTest {
    @Test void readingStructureAndAnalyzingTheSameAuthorizedFileInvokeCodingOnlyOnce() {
        var policy=mock(AgentToolPolicyResolver.class);var planner=mock(ProblemAnalysisPlanner.class);
        var direct=mock(DirectAnswerWorkflow.class);var governed=mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var agent=mock(SkillDefinition.class);when(agent.boundMcpToolNames()).thenReturn(List.of());
        var request=InteractionRequest.builder().query("understand and analyze uploaded originals").toolInput(Map.of("__userAssetIds",List.of("a".repeat(64)))).build();
        var context=InteractionContext.builder().build();
        var file=new ProblemAnalysisPlan.FileInput("controlled.xlsx","user-asset:"+"a".repeat(64)+":"+"b".repeat(64),"application/xlsx",100,"b".repeat(64));
        var files=List.of(file);when(direct.describeFiles(request)).thenReturn(files);
        var plan=new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY,"one analysis","files","data","Same delivered evidence",
            List.of(new ProblemAnalysisPlan.Task("read structure",ProblemAnalysisPlan.Intent.DOCUMENT_UNDERSTANDING,List.of("original"),"structure"),
                new ProblemAnalysisPlan.Task("analyze",ProblemAnalysisPlan.Intent.DATA_ANALYSIS,List.of("original"),"report")),"",new ProblemAnalysisPlan.FileAnalysis(files,ProblemAnalysisPlan.ToolDataDependency.NONE));
        when(planner.analyzeFiles(eq(request),eq(context),eq(agent),anyList(),eq(files))).thenReturn(plan);
        when(direct.execute(eq(request),any(),eq(agent))).thenAnswer(call->{
            InteractionContext planned=call.getArgument(1);assertThat(planned.problemAnalysisPlan()).isSameAs(plan);
            assertThat(planned.problemAnalysisPlan().tasks()).hasSize(2);
            return InteractionResponse.builder().answer("reviewed report").build();
        });
        new InteractionWorkflowCoordinator(policy,planner,direct,null).execute(request,context,agent,governed);
        verify(direct,times(1)).execute(eq(request),any(),eq(agent));verifyNoInteractions(governed);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"OPTIONAL","REQUIRED"})
    void mixedSourcesKeepTheGovernedWorkflowAndBothAuthorizedFileReferences(String dependency) {
        var policy=mock(AgentToolPolicyResolver.class);var planner=mock(ProblemAnalysisPlanner.class);
        var direct=mock(DirectAnswerWorkflow.class);var governed=mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var agent=mock(SkillDefinition.class);when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request=InteractionRequest.builder().query("compare").toolInput(Map.of("__userAssetIds",List.of("a".repeat(64),"c".repeat(64)))).build();
        var context=InteractionContext.builder().build();
        var first=new ProblemAnalysisPlan.FileInput("one.xlsx","user-asset:"+"a".repeat(64)+":"+"b".repeat(64),"application/xlsx",100,"b".repeat(64));
        var second=new ProblemAnalysisPlan.FileInput("two.xlsx","user-asset:"+"c".repeat(64)+":"+"d".repeat(64),"application/xlsx",200,"d".repeat(64));
        var files=List.of(first,second);when(direct.describeFiles(request)).thenReturn(files);
        when(policy.planningSnapshot(request,agent)).thenReturn(WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME,"MAINTAINED_DAG"));
        var plan=new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY,"compare","files","data","mixed evidence",
            List.of(new ProblemAnalysisPlan.Task("compare",ProblemAnalysisPlan.Intent.DATA_ANALYSIS,List.of("originals","business source"),"comparison report")),"",
            new ProblemAnalysisPlan.FileAnalysis(files,ProblemAnalysisPlan.ToolDataDependency.valueOf(dependency)));
        when(planner.analyzeFiles(eq(request),eq(context),eq(agent),anyList(),eq(files))).thenReturn(plan);
        when(governed.execute(any(),eq(plan))).thenAnswer(call->{
            InteractionContext planned=call.getArgument(0);assertThat(planned.problemAnalysisPlan().fileAnalysis().inputs()).containsExactly(first,second);
            return InteractionResponse.builder().answer("business evidence required or optional according to plan").build();
        });
        new InteractionWorkflowCoordinator(policy,planner,direct,null).execute(request,context,agent,governed);
        verify(governed,times(1)).execute(any(),eq(plan));verify(direct,never()).execute(any(),any(),any());
        verify(planner,never()).classify(any(),any(),any(),anyList());
    }
    @Test void modelClassifiedKnowledgeQuestionDoesNotRunADataAcquisitionWorkflow() {
        var policy = mock(AgentToolPolicyResolver.class);
        var planner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var governed = mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("explain the concept").build();
        var context = InteractionContext.builder().build();
        var entry = WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "DECLARED_EXECUTION_CONTRACT");
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        when(planner.classify(request, context, agent, entry.toolPurposes())).thenReturn(ProblemAnalysisPlan.TaskType.QA);
        when(direct.execute(eq(request), any(), eq(agent))).thenReturn(InteractionResponse.builder().answer("explanation")
            .metadata(Map.of(WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(WorkflowOutcome.Type.READY_TO_ANSWER,
                "DIRECT_ANSWER_COMPLETED", List.of(), List.of(), true))).build());
        var result = new InteractionWorkflowCoordinator(policy, planner, direct, null).execute(request, context, agent, governed);
        assertThat(result.getMetadata()).containsEntry("taskType", "QA");
        assertThat(result.getAnswer()).isEqualTo("explanation");
        verifyNoInteractions(governed);
    }
    @Test @Timeout(10)
    void nativePlannerOwnsItsChildUntilItReturnsAndUsesCommonCompletion() throws Exception {
        var policy = mock(AgentToolPolicyResolver.class);
        var frontPlanner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var assets = mock(AssetGuidanceInteractionBridge.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("analyze").build();
        var entry = new WorkflowEntryPlan(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "DECLARED_EXECUTION_CONTRACT",
            List.of(Map.of("tool", "opaque", "data_type", "TEMPLATE_QUERY")));
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        when(frontPlanner.classify(eq(request), any(), eq(agent), eq(entry.toolPurposes())))
            .thenReturn(ProblemAnalysisPlan.TaskType.DATA_ANALYSIS);
        var coordinator = new InteractionWorkflowCoordinator(policy, frontPlanner, direct, assets);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> coordinator.execute(request, InteractionContext.builder().build(), agent,
                (context, understanding) -> {
                    assertThat(understanding).isNull();
                    assertThat(context.taskType()).isEqualTo(ProblemAnalysisPlan.TaskType.DATA_ANALYSIS);
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                    return InteractionResponse.builder().answer("verified report")
                        .metadata(Map.of("agent", Map.of("publicStatus", "SUCCESS"))).build();
                }));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verify(frontPlanner).classify(eq(request), any(), eq(agent), eq(entry.toolPurposes()));
            verifyNoMoreInteractions(frontPlanner);
            verifyNoInteractions(direct, assets);
            release.countDown();
            var response = result.get(3, TimeUnit.SECONDS);
            assertThat(response.getMetadata()).containsEntry("workflowEntryPlan", entry)
                .containsEntry("taskType", "DATA_ANALYSIS")
                .containsEntry("runtimeLifecycle", List.of("SELECT_PLANNING_OWNER", "TASK_CLASSIFICATION", "GOVERNED_RUNTIME", "EVALUATE", "COMPLETE"))
                .doesNotContainKeys("problemAnalysisPlan", "capabilityPlan");
            assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("SUCCESS");
            verify(policy, times(1)).planningSnapshot(request, agent);
            verifyNoMoreInteractions(policy);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void failedClassificationDoesNotExecuteAMaintainedWorkflow() {
        var policy = mock(AgentToolPolicyResolver.class);
        var planner = mock(ProblemAnalysisPlanner.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("inspect current observations").build();
        var context = InteractionContext.builder().build();
        var entry = WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "DECLARED_EXECUTION_CONTRACT");
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        var governed = mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var response = new InteractionWorkflowCoordinator(policy, planner, null, null).execute(request, context, agent, governed);
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).type()).isEqualTo(WorkflowOutcome.Type.FAILED);
        verify(planner).classify(request, context, agent, entry.toolPurposes());
        verifyNoInteractions(governed);
    }

    @Test void semanticPlannerReceivesTheEntrySnapshotAndFailureNeverStartsAnotherPlanner() {
        var policy = mock(AgentToolPolicyResolver.class);
        var planner = mock(ProblemAnalysisPlanner.class);
        var direct = mock(DirectAnswerWorkflow.class);
        var assets = mock(AssetGuidanceInteractionBridge.class);
        var governed = mock(InteractionWorkflowCoordinator.GovernedExecution.class);
        var agent = mock(SkillDefinition.class);
        when(agent.boundMcpToolNames()).thenReturn(List.of("opaque"));
        var request = InteractionRequest.builder().query("explain").build();
        var context = InteractionContext.builder().build();
        var entry = new WorkflowEntryPlan(WorkflowEntryPlan.Owner.PROBLEM_ANALYSIS, "SEMANTIC_WORKFLOW_SELECTION",
            List.of(Map.of("tool", "opaque", "data_type", "ASSET_QUERY")));
        when(policy.planningSnapshot(request, agent)).thenReturn(entry);
        when(planner.analyze(request, context, agent, entry.toolPurposes())).thenReturn(ProblemAnalysisPlan.unavailable());
        var response = new InteractionWorkflowCoordinator(policy, planner, direct, assets).execute(request, context, agent, governed);
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).type()).isEqualTo(WorkflowOutcome.Type.FAILED);
        assertThat(response.getMetadata()).containsEntry("runtimeLifecycle",
            List.of("SELECT_PLANNING_OWNER", "PROBLEM_ANALYSIS_PLAN", "EVALUATE", "COMPLETE"));
        verify(planner).analyze(request, context, agent, entry.toolPurposes());
        verify(policy).planningSnapshot(request, agent);
        verifyNoMoreInteractions(policy, planner);
        verifyNoInteractions(direct, assets, governed);
    }
}
