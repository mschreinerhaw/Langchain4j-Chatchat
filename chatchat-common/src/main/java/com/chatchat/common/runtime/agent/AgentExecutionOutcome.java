package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;

import java.util.List;
import java.util.Map;

/** Structured, evidence-addressable result. Free text alone is never a successful contract. */
public record AgentExecutionOutcome(
    String schemaVersion,
    String executionId,
    String providerAgentId,
    Status status,
    List<GroundedClaim> claims,
    List<Artifact> artifacts,
    List<EvidenceRequirement> missingEvidence,
    List<String> limitations,
    String errorCode,
    String errorMessage,
    Map<String, Object> usage,
    Map<String, Object> metadata
) {
    public static final String SCHEMA_VERSION = "agent_execution_outcome.v1";

    public AgentExecutionOutcome {
        schemaVersion = SCHEMA_VERSION;
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId is required");
        providerAgentId = providerAgentId == null ? "" : providerAgentId;
        status = status == null ? Status.FAILED : status;
        claims = claims == null ? List.of() : List.copyOf(claims);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean successful() { return status == Status.COMPLETED || status == Status.PARTIAL; }

    public enum Status {
        COMPLETED, PARTIAL, INPUT_REQUIRED, SUPPLEMENT_EVIDENCE, REPLAN_REQUIRED,
        BLOCKED, FAILED, TIMED_OUT, CANCELLED
    }

    public record GroundedClaim(String claimId, String text, List<String> evidenceIds, double confidence) {
        public GroundedClaim {
            claimId = claimId == null ? "" : claimId;
            text = text == null ? "" : text;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            confidence = Math.max(0, Math.min(1, confidence));
        }
    }

    public record Artifact(String artifactId, String mediaType, String content, Map<String, Object> metadata) {
        public Artifact {
            artifactId = artifactId == null ? "" : artifactId;
            mediaType = mediaType == null || mediaType.isBlank() ? "text/plain" : mediaType;
            content = content == null ? "" : content;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
