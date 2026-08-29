package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorArchitectureTest {

    private static final int MAX_FACADE_LINES = 1_000;
    private static final int ENGINE_MIGRATION_RATCHET_LINES = 7_175;
    private static final int MAX_DOMAIN_COMPONENT_LINES = 1_000;
    private static final int PLANNER_MIGRATION_RATCHET_LINES = 1_750;
    private static final int ANSWER_FINALIZER_MIGRATION_RATCHET_LINES = 1_700;

    @Test
    void publicOrchestratorRemainsABoundedFacade() throws IOException {
        Path moduleRoot = Path.of(System.getProperty("basedir", "."));
        Path source = moduleRoot.resolve(
            "src/main/java/com/chatchat/agents/orchestration/AgentOrchestrator.java");

        assertThat(source).exists();
        assertThat(Files.readAllLines(source))
            .as("AgentOrchestrator is a public facade; domain behavior belongs in subpackages")
            .hasSizeLessThanOrEqualTo(MAX_FACADE_LINES);
    }

    @Test
    void orchestrationEngineCannotRegrowDuringMigration() throws IOException {
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/AgentOrchestrationEngine.java",
            ENGINE_MIGRATION_RATCHET_LINES,
            "The migration ratchet must only move downward as responsibilities leave the engine");
    }

    @Test
    void extractedWorkflowComponentsRemainFocused() throws IOException {
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/workflow/MandatoryWorkflowRecoveryCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Mandatory workflow recovery must remain a focused domain component");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/workflow/MandatoryWorkflowTopology.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Mandatory workflow topology must remain a focused domain component");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/workflow/MandatoryWorkflowRecoveryPolicy.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Mandatory workflow policy must remain a focused domain component");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/workflow/WorkflowConditionEvaluator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Workflow conditions must remain independent from scheduling state");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/AgentPlannerPromptBuilder.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Planner prompt compilation must remain independent from model invocation and repair");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/InterpretationPlanPayloadNormalizer.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Planner wire compatibility must remain independent from validation and candidate selection");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/AgentPlanCandidateScorer.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Plan candidate scoring must remain deterministic and independent from model invocation");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/evidence/InterpretationPlanEvidenceAnalyzer.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "InterpretationPlan evidence analysis must remain independent from scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/AgentPlanEvolutionAuditor.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Plan evolution audit must remain independent from plan execution");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/lifecycle/AgentRunLifecycleCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Persistent AgentRun lifecycle must remain independent from workflow execution");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/lifecycle/AgentRunScopeBinder.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Kernel scope projection must remain independent from workflow execution");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/InterpretationPlanSnapshotService.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "InterpretationPlan persistence must remain independent from workflow scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/protocol/PlannerEnvelopeParser.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Planner protocol repair must remain independent from semantic plan validation");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AgentResultPresentationService.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Tool result presentation must remain independent from answer finalization policy");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AnswerUserFacingPolicy.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "User-facing answer presentation must remain independent from candidate review");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/DeterministicAnswerReportRenderer.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Deterministic report rendering must remain independent from answer selection");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AnswerReviewCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Reviewer execution and fallback must remain independent from final answer policy");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/toolcall/TemplateExecutionContractSelector.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Template discovery-to-execution admission must remain a focused Runtime OS boundary");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/planning/AgentPlanAttributionPolicy.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Guard repair and candidate attribution must remain independent from planner model invocation");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AnswerQualityCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Answer quality coordination must remain independent from final presentation policy");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AnswerEvidenceAuditService.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Evidence audit must remain independent from answer candidate selection");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/analysis/AnalysisDatasetWorker.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Dataset chunking, retry, checkpoint and reduction must remain worker-owned");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/analysis/AnalysisObjectiveContractCompiler.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Question-to-dataset obligations must remain independent from model prompting");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/analysis/AnalysisSemanticContractCompiler.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Producer-declared semantics must remain independent from record-shape profiling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/analysis/AnalysisRecordScopeProfiler.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Returned-record structural profiling must remain deterministic and semantics-free");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/analysis/AnalysisSummaryGovernanceCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Final analysis governance must remain independent from Driver scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/tool/AgentToolCallCoordinator.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Tool execution observation coordination must remain independent from workflow recovery");
    }

    @Test
    void coreServiceMigrationRatchetsPreventFurtherGrowth() throws IOException {
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/AgentPlanner.java",
            PLANNER_MIGRATION_RATCHET_LINES,
            "AgentPlanner must shrink behind AgentPlanningPort");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/AgentWorkflowDecisionEngine.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "AgentWorkflowDecisionEngine must remain bounded behind AgentWorkflowDecisionPort");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/orchestration/answer/AgentAnswerFinalizer.java",
            ANSWER_FINALIZER_MIGRATION_RATCHET_LINES,
            "AgentAnswerFinalizer must shrink behind AgentAnswerFinalizationPort");
    }

    private void assertSourceLineCount(String relativePath, int maximum, String description)
        throws IOException {
        Path source = Path.of(System.getProperty("basedir", ".")).resolve(relativePath);
        assertThat(source).exists();
        assertThat(Files.readAllLines(source)).as(description).hasSizeLessThanOrEqualTo(maximum);
    }
}
