package com.chatchat.common.runtime.summary.analysis.governance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAnalysisWorkerSupervisionTest {

    private final DataAnalysisWorkerSupervision supervision = new DataAnalysisWorkerSupervision();

    @Test
    void driverBarrierRequiresAtLeastOneAcceptedAnalysisAndReconcilesEveryWorker() {
        DataAnalysisWorkerSupervision.WorkerReport accepted = report(
            "a", DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED);
        DataAnalysisWorkerSupervision.WorkerReport rejected = report(
            "b", DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_NOT_PRODUCED);

        DataAnalysisWorkerSupervision.DriverReport partial =
            supervision.reconcile(2, List.of(accepted, rejected));
        DataAnalysisWorkerSupervision.DriverReport blocked =
            supervision.reconcile(1, List.of(rejected));

        assertThat(partial.synthesisReady()).isTrue();
        assertThat(partial.barrierStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.BarrierStatus.READY_WITH_LIMITATIONS);
        assertThat(blocked.synthesisReady()).isFalse();
        assertThat(blocked.barrierStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.BarrierStatus.BLOCKED);
        assertThatThrownBy(() -> supervision.reconcile(2, List.of(accepted)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("every expected Worker");
    }

    @Test
    void degradedNarrativeOpensOnlyTheLimitedSynthesisBarrier() {
        DataAnalysisWorkerSupervision.WorkerReport degraded = report(
            "a", DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_DEGRADED);

        DataAnalysisWorkerSupervision.DriverReport result =
            supervision.reconcile(1, List.of(degraded));

        assertThat(result.synthesisReady()).isTrue();
        assertThat(result.barrierStatus())
            .isEqualTo(DataAnalysisWorkerSupervision.BarrierStatus.READY_WITH_LIMITATIONS);
    }

    private DataAnalysisWorkerSupervision.WorkerReport report(
        String dataset,
        DataAnalysisWorkerSupervision.ProductStatus status
    ) {
        return new DataAnalysisWorkerSupervision.WorkerReport(
            dataset, "task-" + dataset, "worker-1", "SUCCESS", status,
            1, status == DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED ? 1 : 0,
            status == DataAnalysisWorkerSupervision.ProductStatus.ANALYSIS_ACCEPTED ? 0 : 1,
            0, 10L, List.of(), Map.of());
    }
}
