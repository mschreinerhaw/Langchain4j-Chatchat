package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentOrchestratorArchitectureTest {

    private static final int MAX_FACADE_LINES = 1_000;
    private static final int ENGINE_MIGRATION_RATCHET_LINES = 8_700;
    private static final int MAX_DOMAIN_COMPONENT_LINES = 1_000;
    private static final int PLANNER_MIGRATION_RATCHET_LINES = 3_000;
    private static final int ANSWER_FINALIZER_MIGRATION_RATCHET_LINES = 2_800;

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
            "The migration ratchet must only move downward until the engine reaches 1,000 lines");
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
            "src/main/java/com/chatchat/agents/orchestration/answer/AgentResultPresentationService.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Tool result presentation must remain independent from answer finalization policy");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/toolcall/TemplateExecutionContractSelector.java",
            MAX_DOMAIN_COMPONENT_LINES,
            "Template discovery-to-execution admission must remain a focused Runtime OS boundary");
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
