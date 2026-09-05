package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Builds, dispatches and reconciles domain-neutral Worker analysis tasks. */
public final class AnalysisDispatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisDispatchCoordinator.class);

    private final DatasetAnalysisNode worker;
    private final AnalysisProgressRecorder progressRecorder;
    private final Configuration configuration;
    private DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryProtocol;
    private ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> dispatcher;

    public AnalysisDispatchCoordinator(
        DatasetAnalysisNode worker,
        AnalysisProgressRecorder progressRecorder,
        Configuration configuration,
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> summaryProtocol,
        ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> dispatcher
    ) {
        this.worker = worker;
        this.progressRecorder = progressRecorder;
        this.configuration = configuration;
        this.summaryProtocol = summaryProtocol;
        this.dispatcher = dispatcher;
    }

    public void setSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        if (protocol != null) this.summaryProtocol = protocol;
    }

    public void setDispatcher(
        ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> dispatcher
    ) {
        if (dispatcher != null) this.dispatcher = dispatcher;
    }

    public DispatchBatch dispatch(DispatchRequest request) {
        List<AnalysisTask> tasks = new ArrayList<>();
        Map<String, String> taskIdsByDataset = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        int index = 0;
        for (DatasetInput dataset : request.datasets()) {
            index++;
            int occurrence = occurrences.merge(dataset.reference(), 1, Integer::sum);
            String evidenceReference = occurrence == 1
                ? dataset.reference() : dataset.reference() + "#occurrence-" + occurrence;
            Map<String, Object> governedContext = summaryProtocol.govern(
                evidenceReference,
                AgentRoleAnalysisContext.attach(dataset.analysisContext(), request.runtimeAttributes()),
                dataset.records());
            String inputSha256 = ModelProtocolJson.sha256Hex(Map.of(
                "schemaVersion", AnalysisTask.SCHEMA_VERSION,
                "datasetReference", evidenceReference,
                "records", dataset.records(),
                "analysisContext", governedContext,
                "userObjective", nonNull(request.userQuestion()),
                "modelName", nonNull(request.modelName()),
                "maximumChunkRows", configuration.maximumChunkRows(),
                "maximumChunkChars", configuration.maximumChunkChars(),
                "maximumRetries", configuration.maximumRetries()));
            String taskId = request.isolationScope().partitionKey() + ":" + evidenceReference;
            Map<String, Object> evidenceLocator = new LinkedHashMap<>();
            evidenceLocator.put("datasetReference", evidenceReference);
            Object canonicalPath = governedContext.get("canonicalPath");
            if (canonicalPath != null) {
                evidenceLocator.put("canonicalPath", canonicalPath);
            }
            evidenceLocator.put("sourcePayloadPreservation",
                governedContext.getOrDefault("sourcePayloadPreservation", Map.of()));
            AnalysisTask task = new AnalysisTask(
                AnalysisTask.SCHEMA_VERSION, taskId, inputSha256, request.isolationScope(),
                evidenceReference, index, request.datasets().size(), governedContext,
                evidenceLocator, dataset.records(), request.userQuestion(), request.modelName(),
                configuration.maximumChunkRows(), configuration.maximumChunkChars(),
                configuration.spillThresholdBytes(), configuration.maximumRetries(),
                configuration.heartbeatTimeoutMs(), 1);
            tasks.add(task);
            taskIdsByDataset.put(evidenceReference, taskId);
        }
        if (tasks.isEmpty()) return DispatchBatch.disabled();
        ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> dispatched = dispatcher.dispatch(
            tasks,
            (task, reporter) -> {
                checkCancelled(request.cancellationCheck());
                return worker.execute(request.model(), task, reporter, request.cancellationCheck(),
                    () -> checkCancelled(request.cancellationCheck()));
            },
            request.cancellationCheck(),
            progress -> progressRecorder.record(
                request.runtimeAttributes(), request.isolationScope(), progress));
        log.info("analysisTaskDriverDispatched mode={} taskCount={} workerCount={}",
            dispatched.mode(), dispatched.taskCount(), dispatched.workerCount());
        return new DispatchBatch(dispatched, tasks, taskIdsByDataset);
    }

    private void checkCancelled(BooleanSupplier cancellationCheck) {
        if (Thread.currentThread().isInterrupted()
            || (cancellationCheck != null && cancellationCheck.getAsBoolean())) {
            throw new CancellationException("Agent task cancelled");
        }
    }

    private String nonNull(String value) { return value == null ? "" : value; }

    public record Configuration(
        int maximumChunkRows,
        int maximumChunkChars,
        int spillThresholdBytes,
        int maximumRetries,
        long heartbeatIntervalMs,
        long heartbeatTimeoutMs
    ) {}

    public record DatasetInput(
        String reference,
        Map<String, Object> analysisContext,
        List<Map<String, Object>> records
    ) {
        public DatasetInput {
            analysisContext = analysisContext == null ? Map.of() : Map.copyOf(analysisContext);
            records = records == null ? List.of() : List.copyOf(records);
        }
    }

    public record DispatchRequest(
        ChatModel model,
        String userQuestion,
        String modelName,
        List<DatasetInput> datasets,
        GovernanceIsolationScope isolationScope,
        Map<String, Object> runtimeAttributes,
        BooleanSupplier cancellationCheck
    ) {
        public DispatchRequest {
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
            runtimeAttributes = runtimeAttributes == null ? Map.of() : runtimeAttributes;
            cancellationCheck = cancellationCheck == null ? () -> false : cancellationCheck;
        }
    }

    public record Outcome(
        AnalysisDatasetSummary summary,
        String status,
        String workerId,
        long durationMs,
        String error
    ) {
        public boolean success() {
            return summary != null && !"FAILED".equalsIgnoreCase(status);
        }

        private static Outcome failed(String status, String workerId, long durationMs, String error) {
            return new Outcome(null, fallback(status, "FAILED"), fallback(workerId, "unknown-worker"),
                Math.max(0L, durationMs), fallback(error, "unknown worker failure"));
        }

        private static String fallback(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    public static final class DispatchBatch implements AutoCloseable {
        private final ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> dispatched;
        private final Map<String, AnalysisTask> tasksById;
        private final Map<String, String> taskIdsByDataset;

        private DispatchBatch(ModelSummaryDispatcher.DispatchBatch<AnalysisTaskResult> dispatched,
                              List<AnalysisTask> tasks,
                              Map<String, String> taskIdsByDataset) {
            this.dispatched = dispatched;
            Map<String, AnalysisTask> indexed = new LinkedHashMap<>();
            tasks.forEach(task -> indexed.put(task.taskId(), task));
            this.tasksById = Map.copyOf(indexed);
            this.taskIdsByDataset = Map.copyOf(taskIdsByDataset);
        }

        private static DispatchBatch disabled() {
            return new DispatchBatch(null, List.of(), Map.of());
        }

        public Outcome await(String datasetReference) {
            String taskId = taskIdsByDataset.get(datasetReference);
            if (taskId == null || dispatched == null) {
                return Outcome.failed("MISSING", "driver", 0L, "missing dispatched analysis task");
            }
            AnalysisTask task = tasksById.get(taskId);
            AnalysisTaskResult result = dispatched.await(taskId);
            if (result == null || task == null || !task.taskId().equals(result.taskId())
                || !task.inputSha256().equals(result.inputSha256()) || result.summary() == null
                || "FAILED".equalsIgnoreCase(result.status())) {
                log.warn("analysisTaskDriverFallback taskId={} status={} error={}", taskId,
                    result == null ? "MISSING" : result.status(),
                    result == null ? "missing worker result" : result.error());
                return Outcome.failed(result == null ? "MISSING" : result.status(),
                    result == null ? "unknown-worker" : result.workerId(),
                    result == null ? 0L : result.durationMs(),
                    result == null ? "missing worker result" : result.error());
            }
            task.isolationScope().requireSamePartition(result.summary().isolationScope());
            return new Outcome(result.summary(), Outcome.fallback(result.status(), "SUCCESS"),
                Outcome.fallback(result.workerId(), "unknown-worker"),
                Math.max(0L, result.durationMs()), "");
        }

        public boolean isParallel() {
            return dispatched != null && dispatched.taskCount() > 1 && dispatched.workerCount() > 1;
        }
        public int taskCount() { return dispatched == null ? 0 : dispatched.taskCount(); }
        public int workerCount() { return dispatched == null ? 0 : dispatched.workerCount(); }
        public String mode() { return dispatched == null ? "NONE" : dispatched.mode(); }
        @Override public void close() { if (dispatched != null) dispatched.close(); }
    }
}
