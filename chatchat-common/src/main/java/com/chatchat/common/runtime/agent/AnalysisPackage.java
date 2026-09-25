package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.capability.CapabilityId;

import java.util.Map;

/** Versioned, minimized evidence-first input for a domain intelligence provider. */
public record AnalysisPackage(String schemaVersion, AgentExecutionRequest.TaskContract task,
                              CapabilityId capability, EvidenceBundle evidence,
                              AgentExecutionRequest.Constraints constraints,
                              AgentExecutionRequest.OutputContract outputContract,
                              Map<String, String> traceContext) {
    public static final String SCHEMA_VERSION = "analysis_package.v1";

    public static AnalysisPackage from(AgentExecutionRequest projected) {
        return new AnalysisPackage(SCHEMA_VERSION, projected.task(), projected.capability(),
            projected.evidence(), projected.constraints(), projected.outputContract(),
            Map.of("executionId", projected.executionId(), "tenantId", projected.scope().tenantId()));
    }
}
