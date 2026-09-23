package com.chatchat.runtime.temporal.core;

import com.chatchat.common.runtime.analysis.workflow.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.workflow.ComputationEvidence;
import com.chatchat.common.runtime.analysis.workflow.DocumentAnalysisEvidence;
import com.chatchat.common.runtime.analysis.workflow.ExternalResearchEvidence;
import com.chatchat.common.runtime.analysis.workflow.StandardWorkflowPlan;
import com.chatchat.common.runtime.analysis.workflow.StructuredDataEvidence;
import com.chatchat.common.runtime.analysis.workflow.ToolAnalysisEvidence;
import com.chatchat.common.runtime.analysis.workflow.WorkflowPlan;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Temporal-only serialization metadata for framework-neutral Runtime OS contracts. */
final class TemporalRuntimeObjectMapper {
    private TemporalRuntimeObjectMapper() { }

    static ObjectMapper configure(ObjectMapper source) {
        ObjectMapper mapper = source == null ? new ObjectMapper() : source.copy();
        mapper.addMixIn(WorkflowPlan.class, WorkflowPlanTypes.class);
        mapper.addMixIn(AnalysisEvidence.class, AnalysisEvidenceTypes.class);
        return mapper;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "runtimeType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = StandardWorkflowPlan.class, name = "standard")
    })
    private interface WorkflowPlanTypes { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "evidenceType")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DocumentAnalysisEvidence.class, name = "document"),
        @JsonSubTypes.Type(value = StructuredDataEvidence.class, name = "structured_data"),
        @JsonSubTypes.Type(value = ToolAnalysisEvidence.class, name = "tool"),
        @JsonSubTypes.Type(value = ComputationEvidence.class, name = "computation"),
        @JsonSubTypes.Type(value = ExternalResearchEvidence.class, name = "external_research")
    })
    private interface AnalysisEvidenceTypes { }
}
