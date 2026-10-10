package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.model.AnalysisReportContract;
import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.analysis.AnalysisEvidenceSpillStore;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import dev.langchain4j.model.chat.ChatModel;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs against a deployed Boot classpath; scripted model fixture, not a real analysis. */
public final class ModelSovereigntyReleaseProbe {
    public static void main(String[] args) {
        var calls = new AtomicInteger();
        var assessment = Map.of("evidenceStatus", "COMPLETE", "missingEvidence", List.of("source#chunk2"),
            "requiresReanalysis", true, "claims", List.of(Map.of("claimId", "C1",
                "scope", Map.of("temporalScope", "LONG_TERM", "precision", "EXACT"),
                "references", List.of(Map.of("datasetReference", "source", "record", 1,
                    "fieldPath", List.of("nonexistent"), "semantics", Map.of("temporalScope", "SNAPSHOT", "precision", "ROUNDED"))))));
        String report = "The model chooses to publish its interpretation.";
        var model = (ChatModel) Proxy.newProxyInstance(ChatModel.class.getClassLoader(), new Class<?>[]{ChatModel.class},
            (proxy, method, values) -> {
                if ("chat".equals(method.getName()) && values != null && values.length == 1 && values[0] instanceof String) {
                    calls.incrementAndGet();
                    return ModelProtocolJson.compact(Map.of("schemaVersion", "model_native_analysis.v1", "completed", true,
                        "reportMarkdown", report, "evidenceAssessment", assessment));
                }
                throw new UnsupportedOperationException(method.getName());
            });
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("recordAnalysisExcludedDatasets", List.of(Map.of("datasetReference", "failed-source", "accountingStatus", "FAILED")));
        var result = new ModelNativeAnalysisHarness(8).execute("Analyze using your chosen scope",
            List.of(new Dataset("source", Map.of(), List.of(Map.of("value", 42)))), model,
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            AnalysisEvidenceSpillStore.disabled(), metadata, () -> {}, span -> {});
        var audit = (Map<?, ?>) metadata.get("modelEvidenceAssessmentAudit");
        if (calls.get() != 1 || !report.equals(result.markdown()) || metadata.containsKey("evidenceClaimValidation")
            || !"NONE".equals(audit.get("publicationEffect")) || !assessment.equals(audit.get("assessment"))
            || !audit.toString().contains("FIELD_PATH_NOT_FOUND")
            || ((List<?>) metadata.get("modelEvidenceAssessmentHistory")).size() != 1)
            throw new IllegalStateException("Model autonomy or audit preservation failed");
        metadata.put("modelNativeHarnessActive", true);
        metadata.put("analysisExecutionStatus", "PARTIALLY_COMPLETED");
        metadata.put("analysisReportContract", AnalysisReportContract.modelReport(report, 0, 0, 0).toMap());
        var finalizer = new AgentAnswerFinalizer(
            (chatModel, query, prompt, observations, answer) -> new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok"),
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt"));
        var published = finalizer.finishReviewedAnswer(null, "Analyze", null, List.of(), metadata, List.of(), report, () -> false, "completed");
        var summary = (Map<?, ?>) published.metadata().get("analysisSummaryResult");
        var evidence = (Map<?, ?>) summary.get("evidence");
        if (!report.equals(published.answer()) || !audit.equals(evidence.get("modelEvidenceAssessmentAudit"))
            || !metadata.get("modelEvidenceAssessmentHistory").equals(evidence.get("modelEvidenceAssessmentHistory"))
            || !"PARTIALLY_COMPLETED".equals(published.metadata().get("analysisExecutionStatus")))
            throw new IllegalStateException("Final assembly lost audit, execution status or model prose");
        System.out.println(ModelProtocolJson.compact(Map.of("status", "PASS", "modelCalls", calls.get(),
            "publicationEffect", audit.get("publicationEffect"), "reportPreserved", true,
            "invalidReferenceRecorded", true, "modelAssessmentPreserved", true,
            "runtimeClaimGatePresent", false, "finalSummaryAuditPreserved", true,
            "executionPartialStatusPreserved", true, "fixture", "SCRIPTED_MODEL_NOT_REAL_ANALYSIS")));
    }
}
