package com.chatchat.agents.runtime.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Supplies the immutable DAG governance contract used by planning and execution. */
public interface DagGovernanceContractProvider {

    String CONTRACT_ATTRIBUTE = "dagGovernanceContract";
    String CONTRACT_KEY = "runtime_dag_governance";
    String INITIAL_VERSION = "runtime_dag_governance.v1";

    ContractSnapshot activeContract();

    static DagGovernanceContractProvider builtInFallback() {
        ContractSnapshot snapshot = new ContractSnapshot(
            INITIAL_VERSION,
            CONTRACT_KEY,
            INITIAL_VERSION,
            defaultV1Rules(),
            "built-in-test-fallback"
        );
        return () -> snapshot;
    }

    static Map<String, Object> defaultV1Rules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("contractVersion", INITIAL_VERSION);
        rules.put("authorityOrder", List.of(
            "USER_WORKFLOW_SNAPSHOT",
            "DETERMINISTIC_RUNTIME_AND_SCHEMA",
            "TOOL_PERMISSION_AND_CONFIRMATION",
            "MCP_EXECUTION_EVIDENCE",
            "MODEL_DECISION"
        ));
        rules.put("topology", Map.of(
            "requireUniqueNodeIds", true,
            "rejectCycles", true,
            "requireDeclaredDependencies", true,
            "requireToolAuthorization", true
        ));
        rules.put("execution", Map.of(
            "readyNodePolicy", "ALL_REQUIRED_DEPENDENCIES_COMMITTED",
            "continueIndependentBranches", true,
            "nodeFailureIsTaskTerminal", false,
            "allowParallelReadyNodes", true,
            "deliverCommittedPartialEvidence", true
        ));
        rules.put("repair", Map.of(
            "deterministicRepairFirst", true,
            "modelMayChangeAuthoritativeTopology", false,
            "requireAuditEvent", true,
            "requireRevalidationAfterRepair", true
        ));
        rules.put("retry", Map.of(
            "requireChangedInputOrTransientFailure", true,
            "requireIdempotencyForSideEffects", true,
            "boundedAttempts", true
        ));
        rules.put("persistence", Map.of(
            "pinContractToExecutionSnapshot", true,
            "verifyChecksumAtStartup", true,
            "allowStartupOverwrite", false
        ));
        rules.put("immutable", true);
        return Map.copyOf(rules);
    }

    record ContractSnapshot(
        String contractId,
        String contractKey,
        String contractVersion,
        Map<String, Object> rules,
        String checksumSha256
    ) {
        public ContractSnapshot {
            rules = rules == null ? Map.of() : Map.copyOf(rules);
        }

        public Map<String, Object> toRuntimeAttribute() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("contractId", contractId);
            value.put("contractKey", contractKey);
            value.put("contractVersion", contractVersion);
            value.put("checksumSha256", checksumSha256);
            value.put("rules", rules);
            return Map.copyOf(value);
        }
    }
}
