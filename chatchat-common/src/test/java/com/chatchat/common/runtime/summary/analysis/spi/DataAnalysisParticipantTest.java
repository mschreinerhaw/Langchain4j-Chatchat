package com.chatchat.common.runtime.summary.analysis.spi;

import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisAssignment;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisIsolationScope;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisScope;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisWork;

import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAnalysisParticipantTest {

    private final DataAnalysisIsolationScope isolation = new TestIsolationScope("tenant/run");

    @Test
    void usesOneImmutableAssignmentContractForDatasetAndCollectionScopes() {
        DataAnalysisAssignment dataset = assignment(
            DataAnalysisScope.DATASET, List.of("orders"));
        DataAnalysisAssignment collection = assignment(
            DataAnalysisScope.ASSIGNED_DATASET_COLLECTION,
            List.of("orders-summary", "assets-summary"));

        assertThat(dataset.toMap().get("executionSteps")).isEqualTo(List.of(
            "VALIDATE_ASSIGNMENT", "ANALYZE_ASSIGNED_EVIDENCE",
            "PRODUCE_SCOPED_SUMMARY", "RECONCILE_INPUT_LINEAGE"));
        assertThat(collection.inputReferences())
            .containsExactly("orders-summary", "assets-summary");
        assertThat(dataset.idempotencyKey()).isEqualTo("assignment:sha-256");
    }

    @Test
    void rejectsScopesAndInputShapesOutsideParticipantAssignment() {
        DataAnalysisParticipant<Void, TestWork, String> worker = participant(
            Set.of(DataAnalysisScope.DATASET));
        TestWork collection = new TestWork(assignment(
            DataAnalysisScope.ASSIGNED_DATASET_COLLECTION, List.of("a", "b")));

        assertThatThrownBy(() -> worker.analyze(null, collection,
            ModelSummaryProgressReporter.NOOP, () -> false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported analysis scope");
        assertThatThrownBy(() -> assignment(DataAnalysisScope.DATASET, List.of("a", "b")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one");
    }

    @Test
    void validatesCancellationBeforeImplementationReceivesEvidence() {
        DataAnalysisParticipant<Void, TestWork, String> worker = participant(
            Set.of(DataAnalysisScope.DATASET));

        assertThatThrownBy(() -> worker.analyze(null,
            new TestWork(assignment(DataAnalysisScope.DATASET, List.of("orders"))),
            ModelSummaryProgressReporter.NOOP, () -> true))
            .isInstanceOf(java.util.concurrent.CancellationException.class);
    }

    @Test
    void defensivelyCopiesAssignmentCollections() {
        Map<String, Object> mutableContext = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("purpose", "trading preference");
        mutableContext.put("business", nested);
        DataAnalysisAssignment assignment = new DataAnalysisAssignment(
            DataAnalysisAssignment.SCHEMA_VERSION, "assignment", "sha-256",
            DataAnalysisScope.DATASET, isolation, "analyze trading preference",
            List.of("orders"), mutableContext, 1_000, 1);
        mutableContext.put("unexpected", true);
        nested.put("unexpectedNested", true);

        assertThat(assignment.analysisContext()).doesNotContainKey("unexpected");
        assertThat(((Map<?, ?>) assignment.analysisContext().get("business"))
            .containsKey("unexpectedNested")).isFalse();
        assertThatThrownBy(() -> assignment.analysisContext().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private DataAnalysisAssignment assignment(DataAnalysisScope scope, List<String> inputs) {
        return new DataAnalysisAssignment(DataAnalysisAssignment.SCHEMA_VERSION,
            "assignment", "sha-256", scope, isolation, "analyze trading preference",
            inputs, Map.of("purpose", "trading preference"), 1_000, 1);
    }

    private DataAnalysisParticipant<Void, TestWork, String> participant(
        Set<DataAnalysisScope> supportedScopes
    ) {
        return new DataAnalysisParticipant<>() {
            @Override
            public Set<DataAnalysisScope> supportedScopes() {
                return supportedScopes;
            }

            @Override
            public String analyzeAssigned(Void model, TestWork work,
                                          ModelSummaryProgressReporter progressReporter,
                                          java.util.function.BooleanSupplier cancellationCheck) {
                return work.assignment().assignmentId();
            }

            @Override
            public void reconcile(TestWork work, String result) {
                if (!work.assignment().assignmentId().equals(result)) {
                    throw new IllegalStateException("assignment lineage mismatch");
                }
            }
        };
    }

    private record TestWork(DataAnalysisAssignment assignment) implements DataAnalysisWork { }

    private record TestIsolationScope(String partitionKey) implements DataAnalysisIsolationScope {
        @Override public String schemaVersion() { return "test.v1"; }
        @Override public boolean samePartition(DataAnalysisIsolationScope other) {
            return other != null && partitionKey.equals(other.partitionKey());
        }
        @Override public Map<String, Object> toMap() {
            return Map.of("schemaVersion", schemaVersion(), "partitionKey", partitionKey);
        }
    }
}
