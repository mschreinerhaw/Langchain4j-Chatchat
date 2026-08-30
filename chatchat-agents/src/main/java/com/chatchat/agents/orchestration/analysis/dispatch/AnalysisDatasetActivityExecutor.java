package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;
import dev.langchain4j.model.chat.ChatModel;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Process-local implementation used by the durable dataset Activity adapter. */
public final class AnalysisDatasetActivityExecutor implements AnalysisDatasetExecutionPort {

    private final AgentChatModelResolver modelResolver;
    private final AnalysisDatasetWorker datasetWorker;

    public AnalysisDatasetActivityExecutor(
        AgentChatModelResolver modelResolver,
        AnalysisDatasetWorker datasetWorker
    ) {
        this.modelResolver = modelResolver;
        this.datasetWorker = datasetWorker;
    }

    @Override
    public AnalysisTaskResult execute(
        AnalysisTask task,
        ModelSummaryProgressReporter progressReporter
    ) {
        if (task == null) throw new IllegalArgumentException("Analysis task is required");
        long startedAt = System.nanoTime();
        String executorId = "temporal-analysis-activity";
        try {
            ChatModel model = modelResolver.resolveChatModel(task.modelName());
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted();
            AnalysisDatasetSummary summary = datasetWorker.analyze(
                model, task, progressReporter, cancelled);
            task.isolationScope().requireSamePartition(summary.isolationScope());
            return AnalysisTaskResult.completed(task, executorId, summary, elapsed(startedAt));
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException failed) {
            return AnalysisTaskResult.failed(task, executorId, elapsed(startedAt), failed);
        }
    }

    private long elapsed(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
