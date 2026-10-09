package com.chatchat.agents.orchestration;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SingleBrainArchitectureTest {
    private final Path source=Path.of(System.getProperty("basedir",".")).resolve("src/main/java/com/chatchat/agents");
    @Test void retiredAnalysisAgentsAndFallbackFlagArePhysicallyAbsent() throws IOException {
        for(String path:new String[]{"orchestration/analysis/dispatch/DatasetAnalysisNode.java",
            "orchestration/analysis/dispatch/AnalysisDispatchCoordinator.java",
            "orchestration/analysis/dispatch/LocalAnalysisTaskDispatcher.java",
            "orchestration/analysis/nodes/merge/StructuredFindingMerger.java",
            "orchestration/analysis/nodes/synthesis/GovernedFinalClaimContract.java",
            "orchestration/analysis/graph/UnifiedQuestionAnalysisGraph.java"}) assertThat(source.resolve(path)).doesNotExist();
        String config=Files.readString(source.resolve("runtime/config/AgentRuntimeProperties.java"));
        assertThat(config).doesNotContain("modelNativeHarnessEnabled","analysisSummaryWorker","analysisPerDatasetWorker");
        assertThat(Files.readString(source.resolve("orchestration/AgentOrchestrationEngine.java")))
            .doesNotContain("driverChallengeRepairRequired","analysisDispatchCoordinator","analysisDatasetWorker","ModelSummaryDispatcher");
    }
    @Test void harnessCapabilitiesHaveNoForcedDatasetSynthesisOrIndependentBusinessPlanning() throws IOException {
        String publication=Files.readString(source.resolve("orchestration/analysis/nodes/synthesis/FinalSynthesisNode.java"));
        assertThat(publication).doesNotContain("synthesizeHierarchy","DriverAudit","fallbackSupplier","hierarchicalReducer");
        assertThat(Files.readString(source.resolve("orchestration/analysis/graph/DataWorkspaceOperations.java")))
            .contains("Executors.newFixedThreadPool(concurrency)","QUERY_DATASET","BATCH_MODEL_INFERENCE","retryFailed","PER_RECORD_EXECUTION_STATUS_ONLY")
            .doesNotContain("DATASET_SYNTHESIS","new AgentOrchestrator","planBusiness","classifyBusiness");
        assertThat(Files.readString(source.resolve("orchestration/protocol/RuntimeProtocolConfiguration.java")))
            .doesNotContain("ModelSummaryDispatcher","ModelSummaryReducer");
    }
}
