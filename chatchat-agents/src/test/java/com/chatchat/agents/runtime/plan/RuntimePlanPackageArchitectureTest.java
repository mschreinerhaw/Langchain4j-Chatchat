package com.chatchat.agents.runtime.plan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePlanPackageArchitectureTest {

    private static final Set<String> ROOT_KERNEL_TYPES = Set.of(
        "DagGovernanceContractProvider.java",
        "DagRepairResult.java",
        "EvidenceCompressionGate.java",
        "InterpretationExecutionProtocol.java",
        "InterpretationPlan.java",
        "InterpretationPlanDagConverter.java",
        "InterpretationPlanEventState.java",
        "InterpretationPlanIncrementalRepair.java",
        "InterpretationPlanJsonSchema.java",
        "InterpretationPlanOptimizer.java",
        "InterpretationPlanRewriter.java",
        "InterpretationPlanRuntime.java",
        "InterpretationPlanValidator.java",
        "PlanExecutionGovernor.java",
        "package-info.java"
    );

    @Test
    void rootPackageCannotBecomeAnotherResponsibilityBucket() throws IOException {
        Path root = Path.of(System.getProperty("basedir", "."))
            .resolve("src/main/java/com/chatchat/agents/runtime/plan");
        try (var files = Files.list(root)) {
            Set<String> directJavaFiles = files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".java"))
                .collect(Collectors.toSet());
            assertThat(directJavaFiles)
                .as("New Runtime plan responsibilities must use a named subpackage")
                .isEqualTo(ROOT_KERNEL_TYPES);
        }
    }

    @Test
    void classifiedResponsibilitiesStayInTheirOwnedPackages() {
        assertThat(DiagnosticPackageProbe.TYPES).containsExactlyInAnyOrder(
            "DiagnosticRun", "DiagnosticRunStateMachine");
        assertThat(SelectionPackageProbe.TYPES).containsExactlyInAnyOrder(
            "EvidenceBasedAssetCandidateEvaluator",
            "EvidenceBasedTemplateCandidateEvaluator",
            "RetrievalQualityGate");
        assertThat(PersistencePackageProbe.TYPES).containsExactlyInAnyOrder(
            "InterpretationPlanRecord", "InterpretationPlanStore",
            "NodeAttemptStore", "PlanStepCheckpoint");
    }

    private static final class DiagnosticPackageProbe {
        private static final Set<String> TYPES = Set.of(
            com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRun.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.diagnostic.DiagnosticRunStateMachine.class.getSimpleName());
    }

    private static final class SelectionPackageProbe {
        private static final Set<String> TYPES = Set.of(
            com.chatchat.agents.runtime.plan.selection.EvidenceBasedAssetCandidateEvaluator.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.selection.RetrievalQualityGate.class.getSimpleName());
    }

    private static final class PersistencePackageProbe {
        private static final Set<String> TYPES = Set.of(
            com.chatchat.agents.runtime.plan.persistence.InterpretationPlanRecord.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.persistence.InterpretationPlanStore.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.persistence.NodeAttemptStore.class.getSimpleName(),
            com.chatchat.agents.runtime.plan.persistence.PlanStepCheckpoint.class.getSimpleName());
    }
}
