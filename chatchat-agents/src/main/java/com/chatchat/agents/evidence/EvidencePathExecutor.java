package com.chatchat.agents.evidence;

import java.util.Comparator;
import java.util.List;

public class EvidencePathExecutor {

    private final EvidenceGraphConstraintEngine constraintEngine;

    public EvidencePathExecutor() {
        this(new EvidenceGraphConstraintEngine());
    }

    EvidencePathExecutor(EvidenceGraphConstraintEngine constraintEngine) {
        this.constraintEngine = constraintEngine == null ? new EvidenceGraphConstraintEngine() : constraintEngine;
    }

    public EvidenceExecutionReport execute(EvidenceGraph graph) {
        return execute(graph, EvidenceOsPolicy.productionDefault());
    }

    public EvidenceExecutionReport execute(EvidenceGraph graph, EvidenceOsPolicy policy) {
        return execute(graph, policy, DocumentSelectionContext.unrestricted());
    }

    public EvidenceExecutionReport execute(EvidenceGraph graph,
                                           EvidenceOsPolicy policy,
                                           DocumentSelectionContext selectionContext) {
        if (graph == null || graph.validPaths().isEmpty()) {
            return report(EvidenceExecutionDecision.EMPTY_RESULT, null, List.of("no valid evidence path exists"));
        }
        List<EvidencePath> candidates = graph.validPaths().stream()
            .sorted(Comparator.comparingDouble(EvidencePath::score).reversed())
            .toList();
        EvidenceGraphConstraintEngine.ConstraintResult lastResult = null;
        for (EvidencePath path : candidates) {
            EvidenceGraphConstraintEngine.ConstraintResult result = constraintEngine.evaluate(graph, path, policy, selectionContext);
            if (result.allowed()) {
                return report(EvidenceExecutionDecision.ANSWER_ALLOWED, path, result.reasons());
            }
            lastResult = result;
        }
        return report(EvidenceExecutionDecision.EMPTY_RESULT, candidates.get(0),
            lastResult == null ? List.of("candidate evidence path did not satisfy execution policy") : lastResult.reasons());
    }

    private EvidenceExecutionReport report(EvidenceExecutionDecision decision, EvidencePath path, List<String> reasons) {
        EvidenceAnswerContract contract = new EvidenceAnswerContract(
            EvidenceAnswerContract.CONTRACT_VERSION,
            path == null ? List.of() : path.nodeIds(),
            path == null ? List.of() : path.sqlLineage(),
            decision == EvidenceExecutionDecision.ANSWER_ALLOWED,
            path != null && path.executable(),
            decision
        );
        return new EvidenceExecutionReport(EvidenceExecutionReport.CONTRACT_VERSION, decision, path, contract, reasons);
    }
}
