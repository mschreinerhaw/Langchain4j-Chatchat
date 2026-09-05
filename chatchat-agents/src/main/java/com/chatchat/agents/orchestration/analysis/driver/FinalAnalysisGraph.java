package com.chatchat.agents.orchestration.analysis.driver;

import com.chatchat.agents.orchestration.analysis.graph.AnalysisExecutionGraph;
import com.chatchat.agents.orchestration.analysis.graph.AnalysisFlowState;
import com.chatchat.agents.orchestration.analysis.driver.AnalysisSynthesisCoordinator.FinalModelSynthesisRequest;
import com.chatchat.agents.orchestration.analysis.driver.AnalysisSynthesisCoordinator.FinalSynthesisResult;
import java.util.List;

/** Admission and publication graph; the Driver supplies governed synthesis. */
final class FinalAnalysisGraph {
    FinalSynthesisResult execute(FinalModelSynthesisRequest request,
        java.util.function.Function<FinalModelSynthesisRequest, FinalSynthesisResult> synthesis) {
        request.metadata().remove("analysisFinalAdmissionBlocked");
        var result = new java.util.concurrent.atomic.AtomicReference<FinalSynthesisResult>();
        var execution = new AnalysisExecutionGraph().execute(List.of(
            new AnalysisExecutionGraph.Step("preflight", () -> {
                String status = String.valueOf(request.metadata().get("executionStatus"));
                if ("NEEDS_CLARIFICATION".equals(status)) return AnalysisExecutionGraph.Status.NEEDS_CLARIFICATION;
                if (Boolean.TRUE.equals(request.metadata().get("confirmationRequired")))
                    return AnalysisExecutionGraph.Status.BLOCKED;
                AnalysisFlowState flow = AnalysisFlowState.read(request.metadata());
                return flow == null ? AnalysisExecutionGraph.Status.READY : flow.admission();
            }),
            new AnalysisExecutionGraph.Step("judge_and_compose", () -> {
                result.set(synthesis.apply(request));
                return AnalysisExecutionGraph.Status.READY;
            }),
            new AnalysisExecutionGraph.Step("publication_result", () -> {
                if (!result.get().generated() || result.get().content() == null || result.get().content().isBlank())
                    return AnalysisExecutionGraph.Status.FAILED;
                return "completed_with_limitations".equals(request.stage())
                    || "ANALYZE_WITH_LIMITATIONS".equals(request.metadata().get("evidenceAugmentationDecision"))
                    || !request.coverageComplete() || !request.sourceContentComplete() || !request.evidenceTraceComplete()
                    ? AnalysisExecutionGraph.Status.COMPLETED_WITH_LIMITATIONS : AnalysisExecutionGraph.Status.COMPLETED;
            })), () -> {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("Analysis cancelled");
            });
        request.metadata().put("analysisGraphStatus", execution.status().name());
        request.metadata().put("analysisGraphNodes", execution.nodes());
        if (result.get() == null) {
            request.metadata().remove("analyticalReport");
            request.metadata().put("analysisDriverModelInvoked", false);
            request.metadata().put("interpretationPlanFinalResultProduced", false);
            request.metadata().put("interpretationPlanSummaryGenerated", false);
            request.metadata().put("finalClaimSelectionAccepted", false);
            request.metadata().put("finalPublishedClaimIds", List.of());
            request.metadata().put("analysisFinalAdmissionBlocked", true);
            return new FinalSynthesisResult(admissionMessage(execution.status()), null, false);
        }
        return result.get();
    }

    private String admissionMessage(AnalysisExecutionGraph.Status status) {
        return switch (status) {
            case NEEDS_CLARIFICATION -> "请补充分析所需的数据来源或输入条件后继续。";
            case NEEDS_MORE_EVIDENCE -> "必要证据仍在补充阶段，尚不能生成最终分析报告。";
            case BLOCKED -> "当前分析需要授权或确认，完成后才能继续。";
            case NO_EVIDENCE -> "当前没有满足本次分析要求的可用证据，无法生成可靠结论。";
            default -> "当前分析尚未满足报告生成条件。";
        };
    }

}
