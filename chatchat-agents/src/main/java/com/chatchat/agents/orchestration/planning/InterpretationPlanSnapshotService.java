package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.runtime.plan.DagGovernanceContractProvider;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanDagConverter;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.firstNonBlank;
import static com.chatchat.agents.orchestration.support.AgentValueSupport.stringValue;

/**
 * Owns durable InterpretationPlan snapshots and their governance envelope.
 *
 * <p>The orchestration engine decides <em>when</em> a snapshot is required. This
 * service owns how task identity, DAG projection, governance metadata, status,
 * versioning and persistence failures are handled.</p>
 */
@Slf4j
public final class InterpretationPlanSnapshotService {

    private static final String TASK_ID_ATTRIBUTE = "__agentTaskId";

    private final InterpretationPlanStore store;
    private final InterpretationPlanDagConverter dagConverter;
    private final String runIdAttribute;

    public InterpretationPlanSnapshotService(InterpretationPlanStore store, String runIdAttribute) {
        this(store, new InterpretationPlanDagConverter(), runIdAttribute);
    }

    InterpretationPlanSnapshotService(InterpretationPlanStore store,
                                      InterpretationPlanDagConverter dagConverter,
                                      String runIdAttribute) {
        this.store = store;
        this.dagConverter = dagConverter == null ? new InterpretationPlanDagConverter() : dagConverter;
        this.runIdAttribute = runIdAttribute;
    }

    public void saveGenerated(String stage,
                       InterpretationPlan plan,
                       String tenantId,
                       String requestId,
                       Map<String, Object> runtimeAttributes,
                       Map<String, Object> metadata) {
        if (store == null || plan == null) {
            return;
        }
        String taskId = taskId(runtimeAttributes, requestId);
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String normalizedStage = normalizeStage(stage, "generated");
        try {
            Map<String, Object> dag = dagConverter.convert(plan);
            attachGovernanceContract(dag, runtimeAttributes);
            InterpretationPlanRecord record = store.savePlan(
                firstNonBlank(tenantId, "default"),
                taskId,
                taskId + "-" + normalizedStage,
                plan,
                "GENERATED",
                dag
            );
            recordStoredSnapshot(metadata, record, false);
        } catch (RuntimeException ex) {
            log.warn("Failed to save InterpretationPlan snapshot. taskId={} stage={} error={}",
                taskId, normalizedStage, ex.getMessage());
        }
    }

    public void saveExecution(String stage,
                       InterpretationPlan plan,
                       String tenantId,
                       String requestId,
                       Map<String, Object> runtimeAttributes,
                       Map<String, Object> metadata,
                       InterpretationPlanRuntime.ExecutionResult result) {
        if (store == null || plan == null || result == null) {
            return;
        }
        String taskId = taskId(runtimeAttributes, requestId);
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String normalizedStage = normalizeStage(stage, "execution_result");
        try {
            Map<String, Object> dag = dagConverter.convert(plan, normalizedStage, result);
            attachGovernanceContract(dag, runtimeAttributes);
            InterpretationPlanRecord record = store.savePlan(
                firstNonBlank(tenantId, "default"),
                taskId,
                taskId + "-" + normalizedStage,
                plan,
                result.success() ? "COMPLETED" : "FAILED",
                dag
            );
            recordStoredSnapshot(metadata, record, true);
        } catch (RuntimeException ex) {
            log.warn("Failed to save InterpretationPlan execution snapshot. taskId={} stage={} error={}",
                taskId, normalizedStage, ex.getMessage());
        }
    }

    private String taskId(Map<String, Object> runtimeAttributes, String requestId) {
        return firstNonBlank(
            stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(TASK_ID_ATTRIBUTE)),
            firstNonBlank(
                stringValue(runtimeAttributes == null ? null : runtimeAttributes.get(runIdAttribute)),
                requestId
            )
        );
    }

    private void attachGovernanceContract(Map<String, Object> dag,
                                          Map<String, Object> runtimeAttributes) {
        if (dag == null) {
            return;
        }
        Object contract = runtimeAttributes == null ? null
            : runtimeAttributes.get(DagGovernanceContractProvider.CONTRACT_ATTRIBUTE);
        if (contract != null) {
            dag.put("governanceContract", contract);
        }
    }

    private void recordStoredSnapshot(Map<String, Object> metadata,
                                      InterpretationPlanRecord record,
                                      boolean executionSnapshot) {
        if (metadata == null || record == null) {
            return;
        }
        metadata.put("interpretationPlanId", record.planId());
        metadata.put("interpretationPlanSnapshotVersion", record.version());
        metadata.put("interpretationPlanDagStored", true);
        if (executionSnapshot) {
            metadata.put("interpretationPlanExecutionDagStored", true);
        }
    }

    private String normalizeStage(String stage, String fallback) {
        return stage == null || stage.isBlank() ? fallback : stage.trim();
    }
}
