package com.chatchat.common.runtime.summary.analysis.governance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, framework-neutral Driver view of Worker execution and analysis quality. */
public final class DataAnalysisWorkerSupervision {

    public static final String SCHEMA_VERSION = "data_analysis_worker_supervision.v1";

    public enum ProductStatus {
        ANALYSIS_ACCEPTED,
        ANALYSIS_DEGRADED,
        ANALYSIS_NOT_PRODUCED,
        EXECUTION_FAILED
    }

    public enum BarrierStatus {
        READY,
        READY_WITH_LIMITATIONS,
        BLOCKED
    }

    public record WorkerReport(
        String datasetReference,
        String taskId,
        String workerId,
        String executionStatus,
        ProductStatus productStatus,
        int returnedRecordCount,
        int acceptedChunkCount,
        int rejectedChunkCount,
        int retryCount,
        long durationMs,
        List<String> reasons,
        Map<String, Object> evidence
    ) {
        public WorkerReport {
            datasetReference = text(datasetReference, "result");
            taskId = text(taskId, "unknown-task");
            workerId = text(workerId, "unknown-worker");
            executionStatus = text(executionStatus, "UNKNOWN");
            productStatus = productStatus == null ? ProductStatus.EXECUTION_FAILED : productStatus;
            returnedRecordCount = nonNegative(returnedRecordCount);
            acceptedChunkCount = nonNegative(acceptedChunkCount);
            rejectedChunkCount = nonNegative(rejectedChunkCount);
            retryCount = nonNegative(retryCount);
            durationMs = Math.max(0L, durationMs);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            evidence = evidence == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        }

        public boolean acceptedForSynthesis() {
            return productStatus == ProductStatus.ANALYSIS_ACCEPTED
                || productStatus == ProductStatus.ANALYSIS_DEGRADED;
        }

        public boolean fullyCompliant() {
            return productStatus == ProductStatus.ANALYSIS_ACCEPTED;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("datasetReference", datasetReference);
            value.put("taskId", taskId);
            value.put("workerId", workerId);
            value.put("executionStatus", executionStatus);
            value.put("productStatus", productStatus.name());
            value.put("returnedRecordCount", returnedRecordCount);
            value.put("acceptedChunkCount", acceptedChunkCount);
            value.put("rejectedChunkCount", rejectedChunkCount);
            value.put("retryCount", retryCount);
            value.put("durationMs", durationMs);
            value.put("reasons", reasons);
            value.put("evidence", evidence);
            value.put("acceptedForSynthesis", acceptedForSynthesis());
            value.put("fullyCompliant", fullyCompliant());
            return Collections.unmodifiableMap(value);
        }
    }

    public record DriverReport(
        String schemaVersion,
        int expectedWorkerCount,
        int terminalWorkerCount,
        int acceptedWorkerCount,
        int rejectedWorkerCount,
        BarrierStatus barrierStatus,
        List<WorkerReport> workers
    ) {
        public DriverReport {
            schemaVersion = SCHEMA_VERSION;
            expectedWorkerCount = nonNegative(expectedWorkerCount);
            terminalWorkerCount = nonNegative(terminalWorkerCount);
            acceptedWorkerCount = nonNegative(acceptedWorkerCount);
            rejectedWorkerCount = nonNegative(rejectedWorkerCount);
            barrierStatus = barrierStatus == null ? BarrierStatus.BLOCKED : barrierStatus;
            workers = workers == null ? List.of() : List.copyOf(workers);
            if (terminalWorkerCount != workers.size()
                || terminalWorkerCount != acceptedWorkerCount + rejectedWorkerCount
                || terminalWorkerCount != expectedWorkerCount) {
                throw new IllegalArgumentException(
                    "Driver supervision must reconcile every expected Worker exactly once");
            }
        }

        public boolean synthesisReady() {
            return barrierStatus != BarrierStatus.BLOCKED;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", schemaVersion);
            value.put("expectedWorkerCount", expectedWorkerCount);
            value.put("terminalWorkerCount", terminalWorkerCount);
            value.put("acceptedWorkerCount", acceptedWorkerCount);
            value.put("rejectedWorkerCount", rejectedWorkerCount);
            value.put("barrierStatus", barrierStatus.name());
            value.put("synthesisReady", synthesisReady());
            value.put("workers", workers.stream().map(WorkerReport::toMap).toList());
            return Collections.unmodifiableMap(value);
        }
    }

    public DriverReport reconcile(int expectedWorkerCount, List<WorkerReport> reports) {
        List<WorkerReport> workers = reports == null ? List.of() : List.copyOf(reports);
        int accepted = (int) workers.stream().filter(WorkerReport::acceptedForSynthesis).count();
        int rejected = workers.size() - accepted;
        boolean allFullyCompliant = workers.stream().allMatch(WorkerReport::fullyCompliant);
        BarrierStatus barrier = accepted == expectedWorkerCount && rejected == 0 && allFullyCompliant
            ? BarrierStatus.READY
            : accepted > 0 ? BarrierStatus.READY_WITH_LIMITATIONS : BarrierStatus.BLOCKED;
        return new DriverReport(SCHEMA_VERSION, expectedWorkerCount, workers.size(),
            accepted, rejected, barrier, workers);
    }

    private static int nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("supervision counters must not be negative");
        return value;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
