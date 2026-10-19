package com.chatchat.common.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CodingExecutionEvent(String schemaVersion, String eventId, String taskId, KernelDataScope scope,
    long sequence, long revision, Instant occurredAt, Type type, String operationId, Long attempt,
    CodingTaskOutcome.Status status, CodingErrorCode reasonCode, List<String> evidenceRefs,
    CodingTaskOutcome.Usage usage, String summary, java.util.Map<String, Object> payload) {
    public CodingExecutionEvent(String schemaVersion, String eventId, String taskId, KernelDataScope scope,
            long sequence, long revision, Instant occurredAt, Type type, String operationId, Long attempt,
            CodingTaskOutcome.Status status, CodingErrorCode reasonCode, List<String> evidenceRefs,
            CodingTaskOutcome.Usage usage, String summary) {
        this(schemaVersion, eventId, taskId, scope, sequence, revision, occurredAt, type, operationId,
            attempt, status, reasonCode, evidenceRefs, usage, summary, java.util.Map.of());
    }
    public CodingExecutionEvent withPayload(java.util.Map<String, Object> value) {
        return new CodingExecutionEvent(schemaVersion, eventId, taskId, scope, sequence, revision, occurredAt,
            type, operationId, attempt, status, reasonCode, evidenceRefs, usage, summary, value);
    }
    public enum Type {
        TASK_CREATED, TASK_STARTED, CODE_COMMITTED, EXECUTION_STARTED, EXECUTION_FINISHED,
        VALIDATION_RECORDED, BUDGET_UPDATED, CANCELLATION_REQUESTED, RECOVERY_RECONCILED, TASK_FINISHED
    }
    public CodingExecutionEvent {
        payload = payload == null ? java.util.Map.of() : java.util.Map.copyOf(payload);
        CodingProtocol.version(schemaVersion); eventId = CodingProtocol.ref(eventId, "eventId");
        taskId = CodingProtocol.ref(taskId, "taskId"); scope = CodingProtocol.scope(scope);
        CodingProtocol.count(sequence, "sequence"); CodingProtocol.require(sequence > 0, "event sequence starts at 1");
        CodingProtocol.count(revision, "revision"); occurredAt = CodingProtocol.time(occurredAt, "occurredAt");
        CodingProtocol.required(type, "type"); CodingProtocol.required(status, "status");
        operationId = CodingProtocol.optionalRef(operationId, "operationId");
        CodingProtocol.require((operationId == null) == (attempt == null), "operationId and attempt must occur together");
        if (attempt != null) { CodingProtocol.count(attempt, "attempt"); CodingProtocol.require(attempt > 0, "attempt starts at 1"); }
        if (type == Type.EXECUTION_STARTED || type == Type.EXECUTION_FINISHED || type == Type.CODE_COMMITTED
            || type == Type.VALIDATION_RECORDED) CodingProtocol.required(operationId, "operationId");
        CodingProtocol.require(type != Type.TASK_FINISHED || status.terminal(), "TASK_FINISHED requires a terminal state");
        CodingProtocol.require(type != Type.TASK_CREATED || status == CodingTaskOutcome.Status.CREATED, "TASK_CREATED requires CREATED");
        CodingProtocol.require(type != Type.TASK_STARTED || status == CodingTaskOutcome.Status.RUNNING, "TASK_STARTED requires RUNNING");
        if (type == Type.EXECUTION_STARTED || type == Type.CODE_COMMITTED)
            CodingProtocol.require(status == CodingTaskOutcome.Status.RUNNING, "active execution requires RUNNING");
        if (type == Type.EXECUTION_FINISHED || type == Type.VALIDATION_RECORDED)
            CodingProtocol.require(status != CodingTaskOutcome.Status.CREATED, "execution evidence cannot precede task start");
        if (type == Type.CANCELLATION_REQUESTED) CodingProtocol.require(!status.terminal()
            && reasonCode == CodingErrorCode.CANCEL_REQUESTED, "cancellation request requires an active task and reason");
        evidenceRefs = CodingProtocol.strings(evidenceRefs, "evidenceRefs", true);
        if (type == Type.CODE_COMMITTED || type == Type.EXECUTION_FINISHED || type == Type.VALIDATION_RECORDED)
            CodingProtocol.require(!evidenceRefs.isEmpty(), "committed execution facts require evidence");
        CodingProtocol.required(usage, "usage"); summary = CodingProtocol.text(summary, "summary", 4096);
    }
}
