package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationPlanRuntimeArchitectureTest {

    private static final int RUNTIME_MIGRATION_RATCHET_LINES = 8_730;
    private static final int MAX_FOCUSED_COMPONENT_LINES = 500;

    @Test
    void interpretationPlanRuntimeCannotRegrowDuringDecomposition() throws IOException {
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java",
            RUNTIME_MIGRATION_RATCHET_LINES,
            "InterpretationPlanRuntime must continue shrinking toward a scheduling facade");
    }

    @Test
    void extractedRuntimePoliciesRemainFocused() throws IOException {
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/diagnostic/DiagnosticTemplateMatcher.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "Diagnostic semantic matching must remain independent from plan scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/execution/PlanDagDecisionGuard.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "DAG decision admission must remain independent from tool execution");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/persistence/PlanCheckpointCoordinator.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "checkpoint recovery and persistence must remain independent from scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/review/ToolResultFactInspector.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "tool result fact extraction must remain transport neutral");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/review/LocalToolResultReviewer.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "local fact admission must remain independent from model review");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/protocol/PlanStepInputCompiler.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "input protocol compilation must remain independent from DAG scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/contract/PlanEdgeContractValidator.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "edge contract validation must remain independent from DAG scheduling");
        assertSourceLineCount(
            "src/main/java/com/chatchat/agents/runtime/plan/protocol/ToolProtocolPayloadNavigator.java",
            MAX_FOCUSED_COMPONENT_LINES,
            "tool payload navigation must remain free of business semantics");
    }

    @Test
    void runtimeDelegatesExtractedPoliciesInsteadOfReimplementingThem() throws IOException {
        Path runtime = moduleRoot().resolve(
            "src/main/java/com/chatchat/agents/runtime/plan/InterpretationPlanRuntime.java");
        String source = Files.readString(runtime);

        assertThat(source)
            .contains("DIAGNOSTIC_TEMPLATE_MATCHER.assignments")
            .contains("dagDecisionGuard.validate")
            .contains("checkpointCoordinator.recover")
            .contains("checkpointCoordinator.persist")
            .contains("LOCAL_TOOL_RESULT_REVIEWER.review")
            .contains("STEP_INPUT_COMPILER.compile")
            .contains("EDGE_CONTRACT_VALIDATOR.validate")
            .doesNotContain("private int diagnosticSemanticScore")
            .doesNotContain("private DecisionValidation validateDecision");
    }

    private void assertSourceLineCount(String relativePath, int maximum, String description)
        throws IOException {
        Path source = moduleRoot().resolve(relativePath);
        assertThat(source).exists();
        assertThat(Files.readAllLines(source)).as(description).hasSizeLessThanOrEqualTo(maximum);
    }

    private Path moduleRoot() {
        return Path.of(System.getProperty("basedir", "."));
    }
}
