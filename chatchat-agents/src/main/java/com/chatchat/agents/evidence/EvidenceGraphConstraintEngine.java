package com.chatchat.agents.evidence;

import java.util.ArrayList;
import java.util.List;

public class EvidenceGraphConstraintEngine {

    public ConstraintResult evaluate(EvidenceGraph graph, EvidencePath path, EvidenceOsPolicy policy) {
        return evaluate(graph, path, policy, DocumentSelectionContext.unrestricted());
    }

    public ConstraintResult evaluate(EvidenceGraph graph,
                                     EvidencePath path,
                                     EvidenceOsPolicy policy,
                                     DocumentSelectionContext selectionContext) {
        EvidenceOsPolicy safePolicy = policy == null ? EvidenceOsPolicy.productionDefault() : policy;
        DocumentSelectionContext safeSelection = selectionContext == null ? DocumentSelectionContext.unrestricted() : selectionContext;
        List<String> reasons = new ArrayList<>();
        if (graph == null || graph.nodes().isEmpty()) {
            reasons.add("graph has no evidence nodes");
            return new ConstraintResult(false, EvidenceExecutionDecision.EMPTY_RESULT, reasons);
        }
        if (path == null || path.nodeIds().isEmpty()) {
            reasons.add("no evidence path selected");
            return new ConstraintResult(false, EvidenceExecutionDecision.EMPTY_RESULT, reasons);
        }
        if (safePolicy.requireExecutablePath() && !path.executable()) {
            reasons.add("selected path is not executable");
        }
        if (path.score() < safePolicy.minPathConfidence()) {
            reasons.add("path score below threshold: " + path.score());
        }
        List<EvidenceGraphNode> nodes = path.nodeIds().stream()
            .map(id -> graph.nodes().get(id))
            .toList();
        if (nodes.stream().anyMatch(node -> node == null)) {
            reasons.add("selected path references missing graph nodes");
        }
        if (nodes.stream().anyMatch(node -> node != null && !node.validated())) {
            reasons.add("selected path contains unvalidated nodes");
        }
        if (safeSelection.active() && nodes.stream().anyMatch(node -> node != null && !safeSelection.allowsNode(node))) {
            reasons.add("selected path contains document outside visibility set");
        }
        boolean hasSql = nodes.stream().anyMatch(node -> node != null && isSqlNode(node.type()));
        boolean hasTrustedSql = nodes.stream().anyMatch(node -> node != null && node.type() == EvidenceGraphNodeType.TRUSTED_SQL && node.validated());
        if (safePolicy.requireExecutionVerifiedSql() && hasSql && !hasTrustedSql) {
            reasons.add("SQL path lacks EXECUTION_VERIFIED trusted node");
        }
        if (reasons.isEmpty()) {
            return new ConstraintResult(true, EvidenceExecutionDecision.ANSWER_ALLOWED, List.of("selected evidence path satisfies Evidence OS v2 policy"));
        }
        return new ConstraintResult(false, EvidenceExecutionDecision.EMPTY_RESULT, reasons);
    }

    private boolean isSqlNode(EvidenceGraphNodeType type) {
        return type == EvidenceGraphNodeType.SQL_FRAGMENT
            || type == EvidenceGraphNodeType.NORMALIZED_SQL
            || type == EvidenceGraphNodeType.TRUSTED_SQL;
    }

    public record ConstraintResult(
        boolean allowed,
        EvidenceExecutionDecision decision,
        List<String> reasons
    ) {
        public ConstraintResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
