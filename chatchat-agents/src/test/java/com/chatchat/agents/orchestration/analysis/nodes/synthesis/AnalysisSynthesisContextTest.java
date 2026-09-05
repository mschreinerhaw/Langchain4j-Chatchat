package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.protocol.ModelProtocolJson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSynthesisContextTest {
    @Test void consolidatedInputDoesNotReplayWorkerAnalysisButKeepsItsIdentity() {
        var scope = GovernanceIsolationScope.runtime("tenant", "run", "request", "conversation", "user");
        var worker = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "worker",
            "worker narrative", "SUCCESS", Map.of(), Map.of(), Map.of(), List.of(), Map.of("demandAnalysis",
                Map.of("detail", "UNIQUE_WORKER_DETAIL".repeat(1000))));
        var reducer = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "reducer",
            "consolidated", "SUCCESS", Map.of(), Map.of(), Map.of(), List.of(), Map.of());
        var builder = new AnalysisSynthesisContext();
        String original = ModelProtocolJson.compact(builder.build(List.of(worker), List.of(), Map.of(), Map.of()));
        String compact = ModelProtocolJson.compact(builder.build(List.of(worker), List.of(reducer), Map.of(), Map.of()));
        assertThat(original).contains("UNIQUE_WORKER_DETAIL");
        assertThat(compact).doesNotContain("UNIQUE_WORKER_DETAIL").contains(worker.resultId(), reducer.resultId());
        assertThat(compact.length()).isLessThan(original.length());
    }
}
