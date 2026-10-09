package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReportBlockMarkdownProtocolTest {
    private VisualizationPlanningNode.Result report() {
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(
            ReturnedReportDataset.capture("returned:1", List.of(Map.of("name", "A", "value", 42))))));
        return new VisualizationPlanningNode(VisualizationCapabilityRegistry.active()).execute("""
            # Findings
            The observed value is 42.
            ```json
            {"id":"indicator","type":"metric","chartType":"metric","datasetRef":"returned:1",
             "title":"Indicator","conclusion":"Observed value is 42","reason":"A single verified indicator",
             "encoding":{"x":"name","y":["value"]}}
            ```
            Follow-up action.
            """, catalog);
    }
    @Test void protectsOnlyTheExactBlocksCompiledInThisRun() {
        var report = report();
        var metadata = Map.<String,Object>of("reportBlocks", Map.of("blocks", report.blocks()));
        var protectedReport = ReportBlockMarkdownProtocol.protectVerified(report.markdown(), metadata);
        assertThat(protectedReport.payloads()).hasSize(1);
        assertThat(protectedReport.restore(protectedReport.markdown())).isEqualTo(report.markdown());
        assertThat(ReportBlockMarkdownProtocol.protectVerified(report.markdown().replace("\"value\":42", "\"value\":999"), metadata).payloads()).isEmpty();
        assertThat(ReportBlockMarkdownProtocol.protectVerified(report.markdown(), Map.of()).payloads()).isEmpty();
    }
    @Test void finalAnswerCleanupPreservesVerifiedReportPayloadButDropsUnauditedCopies() {
        var finalizer = new AgentAnswerFinalizer((model, query, prompt, observations, answer) ->
            new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer, "ok"),
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt"));
        var report = report();
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("reportBlocks", Map.of("blocks", report.blocks()));
        assertThat(finalizer.finishExecution(report.markdown(), List.of(), metadata, List.of()).answer())
            .contains("reportBlock", "returned:1", "\"value\":42", "Follow-up action.");
        assertThat(finalizer.finishExecution(report.markdown(), List.of(), new LinkedHashMap<>(), List.of()).answer())
            .contains("Follow-up action.").doesNotContain("reportBlock");
    }
}
