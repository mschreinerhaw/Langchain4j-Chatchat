package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CodingTaskOutcome(String schemaVersion, String taskId, KernelDataScope scope,
    String contractSha256, Status status, long revision, long lastEventSequence,
    Instant createdAt, Instant updatedAt, Instant startedAt, Instant endedAt,
    boolean cancellationRequested, CodingErrorCode reasonCode, String summary, List<String> limitations,
    String inputSnapshotRef, String codeArtifactRef, String executionReceiptRef, String resultDatasetRef,
    String validationRecordRef, String evidenceBundleRef, ValidationStatus validationStatus,
    Reproducibility reproducibility, Usage usage, ExecutionControl executionControl,
    String parentTaskId, String supersedesTaskId,
    Map<CodingTaskContract.RequiredEvidence, Completeness> perArtifactCompleteness) {

    public enum Status {
        CREATED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED, BUDGET_EXCEEDED;
        public boolean terminal() { return this != CREATED && this != RUNNING; }
        public boolean canTransitionTo(Status next) {
            if (next == null) return false;
            if (next == this) return true; // revision-only reconciliation, never a new execution
            if (terminal()) return false;
            return this == RUNNING ? next.terminal()
                : next == RUNNING || next == FAILED || next == CANCELLED || next == BUDGET_EXCEEDED;
        }
    }
    public enum ValidationStatus { PASSED, FAILED, INCONCLUSIVE, NOT_RUN }
    public enum ReproducibilityLevel { FULL, CONDITIONAL, UNAVAILABLE }
    public enum Completeness { COMPLETE, PARTIAL, UNKNOWN }

    public CodingTaskOutcome {
        CodingProtocol.version(schemaVersion); taskId = CodingProtocol.ref(taskId, "taskId");
        scope = CodingProtocol.scope(scope); contractSha256 = CodingProtocol.hash(contractSha256);
        CodingProtocol.required(status, "status"); CodingProtocol.count(revision, "revision");
        CodingProtocol.count(lastEventSequence, "lastEventSequence");
        createdAt = CodingProtocol.time(createdAt, "createdAt"); updatedAt = CodingProtocol.time(updatedAt, "updatedAt");
        CodingProtocol.require(!updatedAt.isBefore(createdAt), "updatedAt precedes createdAt");
        if (startedAt != null) {
            CodingProtocol.time(startedAt, "startedAt");
            CodingProtocol.require(!startedAt.isBefore(createdAt) && !startedAt.isAfter(updatedAt), "invalid startedAt");
        }
        CodingProtocol.require(status != Status.CREATED || startedAt == null, "CREATED cannot have startedAt");
        CodingProtocol.require(status != Status.RUNNING || startedAt != null, "RUNNING requires startedAt");
        CodingProtocol.require(status.terminal() == (endedAt != null), "endedAt must exist exactly for terminal outcomes");
        if (endedAt != null) {
            CodingProtocol.time(endedAt, "endedAt");
            CodingProtocol.require(!endedAt.isBefore(startedAt == null ? createdAt : startedAt)
                && !endedAt.isAfter(updatedAt), "invalid endedAt");
        }
        summary = CodingProtocol.text(summary, "summary", 4096);
        limitations = CodingProtocol.strings(limitations, "limitations", false);
        inputSnapshotRef = CodingProtocol.ref(inputSnapshotRef, "inputSnapshotRef");
        codeArtifactRef = CodingProtocol.optionalRef(codeArtifactRef, "codeArtifactRef");
        executionReceiptRef = CodingProtocol.optionalRef(executionReceiptRef, "executionReceiptRef");
        resultDatasetRef = CodingProtocol.optionalRef(resultDatasetRef, "resultDatasetRef");
        validationRecordRef = CodingProtocol.optionalRef(validationRecordRef, "validationRecordRef");
        evidenceBundleRef = CodingProtocol.optionalRef(evidenceBundleRef, "evidenceBundleRef");
        CodingProtocol.required(validationStatus, "validationStatus");
        CodingProtocol.require(validationStatus == ValidationStatus.NOT_RUN || validationRecordRef != null,
            "validation status requires a validation record");
        CodingProtocol.required(reproducibility, "reproducibility"); CodingProtocol.required(usage, "usage");
        if (executionControl == null) executionControl = new ExecutionControl(null, null, false, false);
        if (executionControl.stopRequestedAt() != null) CodingProtocol.require(
            !executionControl.stopRequestedAt().isBefore(createdAt) && !executionControl.stopRequestedAt().isAfter(updatedAt),
            "stopRequestedAt must lie within the task lifetime");
        if (executionControl.stopReason() == CodingErrorCode.CANCEL_REQUESTED)
            CodingProtocol.require(cancellationRequested, "cancel stop requires cancellationRequested");
        if (status.terminal()) {
            CodingProtocol.require(executionControl.executorStopped() && !executionControl.reconciliationRequired(),
                "terminal outcome requires confirmed executor stop (or proof of no start)");
            if (status != Status.COMPLETED) CodingProtocol.required(reasonCode, "reasonCode");
            if (codeArtifactRef == null || executionReceiptRef == null || resultDatasetRef == null
                || validationRecordRef == null || evidenceBundleRef == null)
                CodingProtocol.require(!limitations.isEmpty(), "missing terminal evidence requires limitations");
        }
        if (status == Status.COMPLETED || status == Status.PARTIAL)
            CodingProtocol.required(startedAt, "startedAt");
        if (status == Status.COMPLETED) {
            CodingProtocol.require(reasonCode == null && !cancellationRequested && executionControl.stopReason() == null,
                "COMPLETED cannot override a stop request");
            CodingProtocol.require((codeArtifactRef != null || CodingProtocol.ANALYSIS_VERSION.equals(schemaVersion)
                    && usage.consumed().codeExecutions()==0) && executionReceiptRef != null && resultDatasetRef != null
                && validationRecordRef != null && evidenceBundleRef != null, "COMPLETED requires all six evidence references");
            CodingProtocol.require(validationStatus != ValidationStatus.NOT_RUN, "COMPLETED requires validation records");
        }
        if (status == Status.CANCELLED) CodingProtocol.require(cancellationRequested
            && reasonCode == CodingErrorCode.CANCEL_REQUESTED
            && executionControl.stopReason() == CodingErrorCode.CANCEL_REQUESTED, "CANCELLED requires accepted cancellation");
        if (status == Status.BUDGET_EXCEEDED) CodingProtocol.require(
            executionControl.stopReason() == CodingErrorCode.BUDGET_EXCEEDED
                || executionControl.stopReason() == CodingErrorCode.DEADLINE_EXCEEDED, "budget stop reason is required");
        if (status == Status.BUDGET_EXCEEDED) CodingProtocol.require(reasonCode == executionControl.stopReason(),
            "budget outcome reason must match the accepted stop reason");
        parentTaskId = CodingProtocol.optionalRef(parentTaskId, "parentTaskId");
        supersedesTaskId = CodingProtocol.optionalRef(supersedesTaskId, "supersedesTaskId");
        CodingProtocol.require(!taskId.equals(parentTaskId) && !taskId.equals(supersedesTaskId), "task cannot reference itself");
        perArtifactCompleteness = perArtifactCompleteness == null ? Map.of() : Map.copyOf(perArtifactCompleteness);
        for (var entry : perArtifactCompleteness.entrySet()) {
            CodingProtocol.require(reference(entry.getKey(), inputSnapshotRef, codeArtifactRef, executionReceiptRef,
                resultDatasetRef, validationRecordRef, evidenceBundleRef) != null, "completeness requires an evidence reference");
            if (status == Status.COMPLETED) CodingProtocol.require(entry.getValue() == Completeness.COMPLETE,
                "COMPLETED cannot contain partial/unknown artifacts");
        }
    }

    private static String reference(CodingTaskContract.RequiredEvidence kind, String input, String code,
        String receipt, String result, String validation, String bundle) {
        return switch (kind) {
            case INPUT_SNAPSHOT -> input; case CODE_ARTIFACT -> code; case EXECUTION_RECEIPT -> receipt;
            case RESULT_DATASET -> result; case VALIDATION_RECORD -> validation; case EVIDENCE_BUNDLE -> bundle;
        };
    }

    /** Pure transition validation; persistence CAS, fencing and budgeting belong to A2. */
    public void validateSuccessor(CodingTaskOutcome next) {
        CodingProtocol.required(next, "next");
        boolean identity = schemaVersion.equals(next.schemaVersion) && taskId.equals(next.taskId) && CodingProtocol.sameScope(scope, next.scope)
            && contractSha256.equals(next.contractSha256) && inputSnapshotRef.equals(next.inputSnapshotRef)
            && createdAt.equals(next.createdAt) && java.util.Objects.equals(parentTaskId, next.parentTaskId)
            && java.util.Objects.equals(supersedesTaskId, next.supersedesTaskId);
        boolean monotonic = revision < CodingProtocol.MAX_SAFE_INTEGER && next.revision == revision + 1
            && next.lastEventSequence >= lastEventSequence && !next.updatedAt.isBefore(updatedAt)
            && (!cancellationRequested || next.cancellationRequested)
            && (startedAt == null || startedAt.equals(next.startedAt))
            && (executionControl.stopReason() == null || executionControl.stopReason() == next.executionControl.stopReason())
            && (executionControl.stopRequestedAt() == null
                || executionControl.stopRequestedAt().equals(next.executionControl.stopRequestedAt()))
            && usage.canAdvanceTo(next.usage);
        boolean terminalFacts = !status.terminal() || java.util.Objects.equals(endedAt, next.endedAt);
        boolean stopped = !(cancellationRequested || executionControl.stopReason() != null)
            || next.status != Status.COMPLETED;
        if (!(identity && monotonic && terminalFacts && stopped && status.canTransitionTo(next.status)))
            throw new CodingContractException(CodingErrorCode.INVALID_STATE_TRANSITION, "Invalid coding task successor");
    }

    /** Confirms task binding without resolving resources or authorizing the caller. */
    public void validateAgainst(CodingTaskContract task) {
        CodingProtocol.required(task, "task");
        CodingProtocol.require(schemaVersion.equals(task.schemaVersion()) && taskId.equals(task.taskId()) && CodingProtocol.sameScope(scope, task.scope())
            && inputSnapshotRef.equals(task.inputSnapshotRef()) && contractSha256.equals(CodingCanonicalHash.sha256(task))
            && usage.budgetId().equals(task.budget().budgetId())
            && usage.costCurrency().equals(task.budget().costCurrency()), "Outcome is not bound to the supplied contract");
        if(status==Status.COMPLETED) {
            CodingProtocol.require(codeArtifactRef!=null || task.permitsTextResult(),"Task requires executed code evidence");
            for(var required:task.outputContract().requiredEvidence())CodingProtocol.require(
                reference(required,inputSnapshotRef,codeArtifactRef,executionReceiptRef,resultDatasetRef,validationRecordRef,evidenceBundleRef)!=null,
                "Completed result is missing required evidence: "+required);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionControl(CodingErrorCode stopReason, Instant stopRequestedAt,
        boolean executorStopped, boolean reconciliationRequired) {
        public ExecutionControl {
            CodingProtocol.require((stopReason == null) == (stopRequestedAt == null), "stop reason and time must occur together");
            if (stopReason != null) {
                CodingProtocol.require(stopReason == CodingErrorCode.CANCEL_REQUESTED || stopReason == CodingErrorCode.BUDGET_EXCEEDED
                    || stopReason == CodingErrorCode.DEADLINE_EXCEEDED, "invalid stop reason");
                CodingProtocol.time(stopRequestedAt, "stopRequestedAt");
            }
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reproducibility(ReproducibilityLevel level, List<String> limitations,
        List<String> dependencyRefs) {
        public Reproducibility {
            CodingProtocol.required(level, "level");
            limitations = CodingProtocol.strings(limitations, "limitations", false);
            dependencyRefs = CodingProtocol.strings(dependencyRefs, "dependencyRefs", true);
            CodingProtocol.require(level == ReproducibilityLevel.FULL || !limitations.isEmpty(), "reproducibility limitations are required");
            CodingProtocol.require(level != ReproducibilityLevel.FULL || (limitations.isEmpty() && !dependencyRefs.isEmpty()),
                "FULL requires immutable dependencies and no known reproducibility limitations");
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Counters(long modelCalls, long modelTokens, long toolCalls, long codeExecutions,
        long cpuMillis, long peakMemoryBytes, long outputBytes, String costAmount) {
        public Counters {
            CodingProtocol.count(modelCalls, "modelCalls"); CodingProtocol.count(modelTokens, "modelTokens");
            CodingProtocol.count(toolCalls, "toolCalls"); CodingProtocol.count(codeExecutions, "codeExecutions");
            CodingProtocol.count(cpuMillis, "cpuMillis"); CodingProtocol.count(peakMemoryBytes, "peakMemoryBytes");
            CodingProtocol.count(outputBytes, "outputBytes"); costAmount = CodingProtocol.decimal(costAmount, "costAmount");
        }
        boolean noLessThan(Counters previous) {
            return modelCalls >= previous.modelCalls && modelTokens >= previous.modelTokens && toolCalls >= previous.toolCalls
                && codeExecutions >= previous.codeExecutions && cpuMillis >= previous.cpuMillis
                && peakMemoryBytes >= previous.peakMemoryBytes && outputBytes >= previous.outputBytes
                && new BigDecimal(costAmount).compareTo(new BigDecimal(previous.costAmount)) >= 0;
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(String budgetId, String costCurrency, Counters consumed, Counters reserved,
        boolean meteringComplete) {
        public Usage {
            budgetId = CodingProtocol.ref(budgetId, "budgetId");
            CodingProtocol.require(costCurrency != null && costCurrency.matches("[A-Z]{3}"), "invalid costCurrency");
            CodingProtocol.required(consumed, "consumed"); CodingProtocol.required(reserved, "reserved");
        }
        boolean canAdvanceTo(Usage next) {
            return budgetId.equals(next.budgetId) && costCurrency.equals(next.costCurrency) && next.consumed.noLessThan(consumed);
        }
    }
}
