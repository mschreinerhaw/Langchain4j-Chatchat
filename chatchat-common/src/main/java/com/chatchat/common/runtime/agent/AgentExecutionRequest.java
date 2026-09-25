package com.chatchat.common.runtime.agent;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.capability.CapabilityId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime-owned, minimized context sent to an agent compute provider. */
public record AgentExecutionRequest(
    String schemaVersion,
    String executionId,
    CapabilityId capability,
    TaskContract task,
    EvidenceBundle evidence,
    Set<CapabilityId> capabilityGrants,
    Constraints constraints,
    OutputContract outputContract,
    KernelDataScope scope,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "agent_execution_request.v1";
    public static final String MODE_METADATA_KEY = "agentExecutionMode";
    public static final String TARGET_AGENT_METADATA_KEY = "targetAgentId";
    public static final String COLLABORATION_TASK_METADATA_KEY = "collaborationTaskId";

    public AgentExecutionRequest {
        schemaVersion = SCHEMA_VERSION;
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId is required");
        if (capability == null) throw new IllegalArgumentException("capability is required");
        if (task == null) throw new IllegalArgumentException("task is required");
        evidence = evidence == null ? EvidenceBundle.empty("no evidence supplied") : evidence;
        capabilityGrants = capabilityGrants == null ? Set.of() : Set.copyOf(capabilityGrants);
        constraints = constraints == null ? Constraints.defaults() : constraints;
        outputContract = outputContract == null ? OutputContract.defaults() : outputContract;
        if (scope == null) throw new IllegalArgumentException("kernel scope is required");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        AgentExecutionMode.parse(metadata.get(MODE_METADATA_KEY));
    }

    public AgentExecutionMode executionMode() {
        return AgentExecutionMode.parse(metadata.get(MODE_METADATA_KEY));
    }

    public record TaskContract(String type, String instruction, Map<String, Object> parameters) {
        public TaskContract {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("task type is required");
            if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("instruction is required");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record Constraints(long timeoutMs, int maxAttempts, boolean citeEvidence,
                              boolean rejectUnsupportedClaims, Set<String> allowedDataDomains) {
        public Constraints {
            timeoutMs = timeoutMs <= 0 ? 60_000 : timeoutMs;
            maxAttempts = Math.max(1, maxAttempts);
            allowedDataDomains = allowedDataDomains == null ? Set.of() : Set.copyOf(allowedDataDomains);
        }
        public static Constraints defaults() { return new Constraints(60_000, 1, true, true, Set.of()); }
    }

    public record OutputContract(String schema, List<String> requiredFields, List<String> acceptedMediaTypes) {
        public OutputContract {
            schema = schema == null || schema.isBlank() ? AgentExecutionOutcome.SCHEMA_VERSION : schema.trim();
            requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
            acceptedMediaTypes = acceptedMediaTypes == null || acceptedMediaTypes.isEmpty()
                ? List.of("application/json", "text/plain") : List.copyOf(acceptedMediaTypes);
        }
        public static OutputContract defaults() { return new OutputContract(null, List.of(), List.of()); }
    }
}
