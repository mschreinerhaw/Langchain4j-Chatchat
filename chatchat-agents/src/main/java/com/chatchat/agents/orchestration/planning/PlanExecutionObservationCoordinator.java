package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.orchestration.evidence.ReviewedPlanTraceProjector;
import com.chatchat.agents.orchestration.tool.ToolObservationBuilder;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chatchat.agents.orchestration.support.AgentValueSupport.*;

/** Projects a completed plan attempt into audit traces, observations and run metadata. */
public final class PlanExecutionObservationCoordinator {

    private final ObjectMapper objectMapper;
    private ToolObservationBuilder observationBuilder;

    public PlanExecutionObservationCoordinator(ObjectMapper objectMapper,
        ToolObservationBuilder observationBuilder) {
        this.objectMapper = objectMapper;
        this.observationBuilder = observationBuilder;
    }

    public void setObservationBuilder(ToolObservationBuilder observationBuilder) {
        this.observationBuilder = observationBuilder;
    }

    public void record(String stage, InterpretationPlanRuntime.ExecutionResult result,
        List<InteractionToolTrace> traces, List<String> observations, Map<String, Object> metadata) {
        if (result == null) return;
        List<Map<String, Object>> records = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution step : result.steps()) {
            Map<String, Object> record = stepRecord(stage, step);
            records.add(record);
            if (step.toolExecution() != null && step.toolExecution().trace() != null) {
                traces.add(ReviewedPlanTraceProjector.project(
                    step.toolExecution().trace(), step.output(), step.metadata(), objectMapper));
            }
            observations.add(stepObservation(stage, step));
            addObservation(observations, record, "templateSelectionFeedbackObservation",
                templateSelectionFeedbackObservation(stage, step));
            addObservation(observations, record, "evidenceEvaluationObservation",
                evidenceEvaluationObservation(step));
            addObservation(observations, record, "canonicalEvidenceObservation",
                canonicalEvidenceObservation(step));
        }
        projectResultMetadata(stage, result, metadata);
        addCandidateList(metadataList(metadata, "interpretationPlanStepExecutions"), records);
    }

    public String templateSelectionFeedbackObservation(String stage,
        InterpretationPlanRuntime.StepExecution step) {
        if (step == null || step.metadata() == null || step.metadata().isEmpty()) return null;
        Map<String, Object> feedback = new LinkedHashMap<>();
        for (String key : List.of("selectedTemplateIds", "rejectedTemplateIds",
            "templateEvaluations", "refinedIntent", "runtimeSelectedTemplateIds",
            "runtimeTemplateCandidateEvaluations", "runtimeTemplateSelectionReason",
            "templateExecutionReview", "templateReselectionRequired")) {
            Object value = step.metadata().get(key);
            if (value != null && !String.valueOf(value).isBlank() && !List.of().equals(value)) {
                feedback.put(key, value);
            }
        }
        if (feedback.isEmpty()) return null;
        feedback.put("schemaVersion", "template_selection_feedback.v1");
        feedback.put("stage", stage);
        feedback.put("stepId", step.stepId());
        feedback.put("toolName", step.toolName());
        return "InterpretationPlan template selection feedback: " + stringify(feedback);
    }

    private Map<String, Object> stepRecord(String stage,
        InterpretationPlanRuntime.StepExecution step) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("stage", stage);
        record.put("stepId", step.stepId());
        record.put("actionType", step.actionType());
        record.put("toolName", step.toolName());
        record.put("success", step.success());
        record.put("durationMs", step.durationMs());
        if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
            record.put("errorMessage", step.errorMessage());
        }
        if (step.metadata() != null && !step.metadata().isEmpty()) {
            record.put("metadata", step.metadata());
        }
        return record;
    }

    private void projectResultMetadata(String stage, InterpretationPlanRuntime.ExecutionResult result,
        Map<String, Object> metadata) {
        String prefix = "interpretationPlan" + capitalize(stage);
        metadata.put(prefix + "Status", result.status());
        metadata.put(prefix + "Success", result.success());
        metadata.put(prefix + "DurationMs", result.durationMs());
        Object diagnosticRun = result.metadata() == null ? null : result.metadata().get("diagnosticRun");
        if (diagnosticRun != null) {
            metadata.put("diagnosticRun", diagnosticRun);
            metadata.put(prefix + "DiagnosticRun", diagnosticRun);
            copyIfPresent(result.metadata(), metadata, "diagnosticCoverage");
            copyIfPresent(result.metadata(), metadata, "diagnosticAssessment");
        }
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            metadata.put(prefix + "Error", result.errorMessage());
        }
    }

    private String stepObservation(String stage, InterpretationPlanRuntime.StepExecution step) {
        if (step.success()) {
            return "InterpretationPlan " + stage + ("final_answer".equals(step.actionType())
                ? " final answer step " + step.stepId() + " completed."
                : " step " + step.stepId() + " "
                    + firstNonBlank(step.toolName(), step.actionType()) + " succeeded.");
        }
        return "InterpretationPlan " + stage + " step " + step.stepId() + " "
            + firstNonBlank(step.toolName(), step.actionType()) + " failed: "
            + firstNonBlank(step.errorMessage(), "unknown error");
    }

    private String canonicalEvidenceObservation(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || !step.success() || step.toolExecution() == null
            || step.toolExecution().output() == null || observationBuilder == null) return null;
        String observation = observationBuilder.buildSuccessObservation(step.toolName(),
            step.toolExecution().output(), stringify(step.output()), step.metadata());
        return hasCanonicalEvidence(observation) ? observation : null;
    }

    private String evidenceEvaluationObservation(InterpretationPlanRuntime.StepExecution step) {
        if (step == null || step.metadata() == null) return null;
        Object evaluation = step.metadata().get("evidenceEvaluation");
        if (!(evaluation instanceof Map<?, ?> map) || map.isEmpty()) return null;
        return "Evidence evaluation (contractVersion=evidence_evaluation_contract_v1): "
            + shortObservationText(stringify(map), 1600);
    }

    private boolean hasCanonicalEvidence(String observation) {
        return observation != null && (observation.contains("Canonical evidence store (contractVersion=evidence_canonical_v1)")
            || observation.contains("Evidence graph execution (contractVersion=evidence_graph_v1)")
            || observation.contains("Evidence OS execution (contractVersion=evidence_os_execution_v2)")
            || observation.contains("Unified evidence context (contractVersion=evidence_v1)")
            || observation.contains("doc://") || observation.contains("web://"));
    }

    private void addObservation(List<String> observations, Map<String, Object> record,
        String marker, String observation) {
        if (observation == null || observation.isBlank()) return;
        observations.add(observation);
        record.put(marker, true);
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) target.put(key, source.get(key));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metadataList(Map<String, Object> metadata, String key) {
        Object existing = metadata.get(key);
        if (existing instanceof List<?> list) return (List<Map<String, Object>>) list;
        List<Map<String, Object>> values = new ArrayList<>();
        metadata.put(key, values);
        return values;
    }

    private String capitalize(String value) {
        return value == null || value.isBlank() ? ""
            : value.substring(0, 1).toUpperCase() + value.substring(1);
    }
}
