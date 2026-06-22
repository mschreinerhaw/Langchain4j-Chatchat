package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class EvidenceExecutionContractValidator {

    public ValidationResult validate(EvidenceExecutionContract contract) {
        List<String> errors = new ArrayList<>();
        if (contract == null) {
            return new ValidationResult(false, List.of("contract is null"));
        }
        requireNonBlank(contract.contractHash(), "contractHash", errors);
        requireNonBlank(contract.graphViewHash(), "graphViewHash", errors);

        Set<String> dagNodeIds = new LinkedHashSet<>();
        for (EvidenceExecutionContract.DagNode node : contract.executionDag().nodes()) {
            dagNodeIds.add(node.id());
            requireConfidence(node.confidence(), "executionDag.nodes[" + node.id() + "].confidence", errors);
        }
        for (EvidenceExecutionContract.DagEdge edge : contract.executionDag().edges()) {
            if (!dagNodeIds.contains(edge.from())) {
                errors.add("executionDag edge from is missing node: " + edge.from());
            }
            if (!dagNodeIds.contains(edge.to())) {
                errors.add("executionDag edge to is missing node: " + edge.to());
            }
            requireConfidence(edge.confidence(), "executionDag.edges[" + edge.from() + "->" + edge.to() + "].confidence", errors);
        }

        Set<String> evidenceRefs = evidenceRefs(contract.evidence());
        for (EvidenceExecutionContract.ExecutionStep step : contract.executionSpec().steps()) {
            if (!dagNodeIds.contains(step.nodeId())) {
                errors.add("executionSpec step nodeId is missing DAG node: " + step.nodeId());
            }
            if (step.source() == null || step.source().isBlank() || !evidenceRefs.contains(step.source())) {
                errors.add("executionSpec step source is missing evidence binding: " + step.source());
            }
            requireConfidence(step.confidence(), "executionSpec.steps[" + step.nodeId() + "].confidence", errors);
        }

        validateEvidenceItems(contract.evidence().direct(), "evidence.direct", errors);
        validateEvidenceItems(contract.evidence().supporting(), "evidence.supporting", errors);
        validateEvidenceItems(contract.evidence().context(), "evidence.context", errors);
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    public void validateOrThrow(EvidenceExecutionContract contract) {
        ValidationResult result = validate(contract);
        if (!result.valid()) {
            throw new IllegalStateException("Invalid evidence execution contract: " + String.join("; ", result.errors()));
        }
    }

    private Set<String> evidenceRefs(EvidenceExecutionContract.EvidenceTiers evidence) {
        Set<String> refs = new LinkedHashSet<>();
        addEvidenceRefs(refs, evidence.direct());
        addEvidenceRefs(refs, evidence.supporting());
        addEvidenceRefs(refs, evidence.context());
        return refs;
    }

    private void addEvidenceRefs(Set<String> refs, List<EvidenceExecutionContract.EvidenceItem> items) {
        for (EvidenceExecutionContract.EvidenceItem item : items == null ? List.<EvidenceExecutionContract.EvidenceItem>of() : items) {
            if (item.refId() != null && !item.refId().isBlank()) {
                refs.add(item.refId());
            }
        }
    }

    private void validateEvidenceItems(List<EvidenceExecutionContract.EvidenceItem> items,
                                       String path,
                                       List<String> errors) {
        for (EvidenceExecutionContract.EvidenceItem item : items == null ? List.<EvidenceExecutionContract.EvidenceItem>of() : items) {
            requireConfidence(item.confidence(), path + "[" + item.nodeId() + "].confidence", errors);
        }
    }

    private void requireNonBlank(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " must be non-empty");
        }
    }

    private void requireConfidence(double value, String field, List<String> errors) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            errors.add(field + " must be within 0..1");
        }
    }

    public record ValidationResult(
        boolean valid,
        List<String> errors
    ) {

        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }
}
