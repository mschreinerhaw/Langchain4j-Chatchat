package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import static com.chatchat.common.runtime.coding.CodingContractTestSupport.*;
import static com.chatchat.common.runtime.coding.CodingTaskOutcome.Status.*;
import static org.assertj.core.api.Assertions.*;

class CodingContractsTest {
    @Test void wireRoundTripsPreserveTypedValuesAndFailedBusinessValidation() throws Exception {
        for (var entry : Map.of("task", CodingTaskContract.class, "outcome", CodingTaskOutcome.class,
            "event", CodingExecutionEvent.class, "error", CodingAgentSPI.Error.class).entrySet()) {
            Object original = JSON.treeToValue(fixture(entry.getKey()), entry.getValue());
            Object restored = JSON.readValue(JSON.writeValueAsBytes(original), entry.getValue());
            assertThat(restored).isEqualTo(original);
        }
        CodingTaskOutcome completed = outcome(COMPLETED, 2);
        assertThat(completed.validationStatus()).isEqualTo(CodingTaskOutcome.ValidationStatus.FAILED);
        completed.validateAgainst(JSON.treeToValue(fixture("task"), CodingTaskContract.class));
    }

    @Test void contractsAreImmutableAndAllowOptionalFutureFields() throws Exception {
        ObjectNode node = fixture("task"); node.put("futureOptionalField", "ignored");
        ((ObjectNode) node.get("objective")).put("futureHint", "ignored");
        CodingTaskContract task = JSON.treeToValue(node, CodingTaskContract.class);
        assertThatThrownBy(() -> task.metadataRefs().add("metadata:other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> task.authorization().allowedTools().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(CodingCanonicalHash.sha256(task)).isEqualTo(fixture("canonical-vector").get("sha256").asText());
    }

    @TestFactory Stream<DynamicTest> exactStateMatrix() {
        Map<CodingTaskOutcome.Status, Set<CodingTaskOutcome.Status>> permitted = Map.of(
            CREATED, Set.of(CREATED, RUNNING, FAILED, CANCELLED, BUDGET_EXCEEDED),
            RUNNING, Set.of(RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED, BUDGET_EXCEEDED));
        return Stream.of(CodingTaskOutcome.Status.values()).flatMap(from -> Stream.of(CodingTaskOutcome.Status.values())
            .map(to -> DynamicTest.dynamicTest(from + " -> " + to, () -> {
                boolean allowed = permitted.getOrDefault(from, Set.of(from)).contains(to);
                assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
            })));
    }

    @Test void transitionsRequireStableIdentityMonotonicRevisionAndAccumulatedUsage() throws Exception {
        outcome(CREATED, 0).validateSuccessor(outcome(RUNNING, 1));
        outcome(RUNNING, 1).validateSuccessor(outcome(COMPLETED, 2));
        CodingTaskOutcome previous = outcome(RUNNING, 1);
        ObjectNode higher = tree(outcome(RUNNING, 2)); set(higher, "/usage/consumed/cpuMillis", 10);
        CodingTaskOutcome consumed = readOutcome(higher); previous.validateSuccessor(consumed);
        ObjectNode lower = tree(outcome(RUNNING, 3));
        assertThatThrownBy(() -> consumed.validateSuccessor(readOutcome(lower))).isInstanceOf(CodingContractException.class);
        assertThatThrownBy(() -> previous.validateSuccessor(outcome(RUNNING, 1))).isInstanceOf(CodingContractException.class);
        ObjectNode crossTenant = tree(outcome(COMPLETED, 2)); set(crossTenant, "/scope/tenantId", "other-tenant");
        assertThatThrownBy(() -> previous.validateSuccessor(readOutcome(crossTenant))).isInstanceOf(CodingContractException.class);
    }

    @Test void acceptedCancellationBlocksCompletionButRetainsCommittedEvidence() throws Exception {
        ObjectNode request = tree(outcome(RUNNING, 1)); set(request, "/cancellationRequested", true);
        set(request, "/executionControl/stopReason", "CANCEL_REQUESTED");
        set(request, "/executionControl/stopRequestedAt", "2026-10-15T10:00:00Z");
        CodingTaskOutcome requested = readOutcome(request);
        assertThatThrownBy(() -> requested.validateSuccessor(outcome(COMPLETED, 2))).isInstanceOf(CodingContractException.class);
        requested.validateSuccessor(outcome(CANCELLED, 2));
        assertThat(outcome(CANCELLED, 2).resultDatasetRef()).isEqualTo("dataset:result-001");
        ObjectNode unconfirmed = tree(outcome(CANCELLED, 2)); set(unconfirmed, "/executionControl/executorStopped", false);
        assertThatThrownBy(() -> readOutcome(unconfirmed)).hasRootCauseInstanceOf(CodingContractException.class);
    }

    @Test void terminalReconciliationCanAddUsageButCannotResumeOrMoveEndTime() throws Exception {
        CodingTaskOutcome completed = outcome(COMPLETED, 2);
        ObjectNode late = tree(outcome(COMPLETED, 3)); set(late, "/usage/consumed/costAmount", "0.01");
        completed.validateSuccessor(readOutcome(late));
        assertThatThrownBy(() -> completed.validateSuccessor(outcome(RUNNING, 3))).isInstanceOf(CodingContractException.class);
        set(late, "/endedAt", "2026-10-15T10:00:00Z");
        assertThatThrownBy(() -> completed.validateSuccessor(readOutcome(late))).isInstanceOf(CodingContractException.class);
    }

    @Test void cancellationBeforeStartAndDeadlineStopHaveExplicitFacts() throws Exception {
        ObjectNode cancelBeforeStart = tree(outcome(CANCELLED, 1)); set(cancelBeforeStart, "/startedAt", null);
        outcome(CREATED, 0).validateSuccessor(readOutcome(cancelBeforeStart));
        ObjectNode deadline = tree(outcome(BUDGET_EXCEEDED, 2));
        set(deadline, "/reasonCode", "DEADLINE_EXCEEDED"); set(deadline, "/executionControl/stopReason", "DEADLINE_EXCEEDED");
        outcome(RUNNING, 1).validateSuccessor(readOutcome(deadline));
        set(deadline, "/executionControl/stopRequestedAt", "2026-10-15T11:00:00Z");
        assertThatThrownBy(() -> readOutcome(deadline)).hasRootCauseInstanceOf(CodingContractException.class);
    }

    @Test void identityHashExcludesTraceButNotInputEnvironmentOrAuthorization() throws Exception {
        CodingTaskContract task = JSON.treeToValue(fixture("task"), CodingTaskContract.class);
        ObjectNode node = fixture("task");
        node.set("scope", tree(new KernelDataScope("tenant-a", "analyst", "new-request", "other-conversation", "run-001", "prod", Map.of("trace", "new"))));
        assertThat(CodingCanonicalHash.sha256(JSON.treeToValue(node, CodingTaskContract.class))).isEqualTo(CodingCanonicalHash.sha256(task));
        for (String field : List.of("inputSnapshotRef", "environmentRef", "validationPlanRef", "workspaceRef")) {
            ObjectNode changed = fixture("task"); changed.put(field, "changed:reference");
            assertThat(CodingCanonicalHash.sha256(JSON.treeToValue(changed, CodingTaskContract.class))).isNotEqualTo(CodingCanonicalHash.sha256(task));
        }
        ObjectNode changed = fixture("task"); set(changed, "/authorization/decisionRef", "auth:changed");
        assertThat(CodingCanonicalHash.sha256(JSON.treeToValue(changed, CodingTaskContract.class))).isNotEqualTo(CodingCanonicalHash.sha256(task));
    }

    @Test void standardErrorsHaveNoVendorPayloadAndUnknownVersionIsNeverDefaulted() throws Exception {
        CodingAgentSPI.Error error = JSON.treeToValue(fixture("error"), CodingAgentSPI.Error.class);
        assertThat(new CodingContractException(error).error()).isEqualTo(error);
        ObjectNode node = fixture("task"); node.put("schemaVersion", "runtime_os.coding.v3");
        assertThatThrownBy(() -> JSON.treeToValue(node, CodingTaskContract.class))
            .rootCause().isInstanceOf(CodingContractException.class)
            .extracting(value -> ((CodingContractException) value).error().code()).isEqualTo(CodingErrorCode.VERSION_UNSUPPORTED);
    }

    @Test void codingProductionSourcesRemainFrameworkAndVendorIndependent() throws Exception {
        Path root = Path.of("src/main/java/com/chatchat/common/runtime/coding");
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                List<String> imports = Files.readAllLines(source).stream().filter(line -> line.startsWith("import ")).toList();
                assertThat(imports).allMatch(line -> line.startsWith("import java.") || line.startsWith("import com.chatchat.common.")
                    || line.startsWith("import com.fasterxml.jackson.annotation."));
            }
        }
    }
}
