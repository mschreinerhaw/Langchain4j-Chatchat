package com.chatchat.runtime.temporal.workflow;

import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchCommand;
import com.chatchat.runtime.temporal.contract.TemporalAnalysisBatchResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Deterministic controller for a bounded wave of dataset-analysis Activities. */
@WorkflowInterface
public interface RuntimeOsAnalysisBatchWorkflow {

    @WorkflowMethod(name = "runtime-os-analysis-batch-v1")
    TemporalAnalysisBatchResult execute(TemporalAnalysisBatchCommand command);

    @SignalMethod(name = "cancel-analysis-task-v1")
    void cancelTask(String taskId);

    @QueryMethod(name = "analysis-batch-status-v1")
    TemporalAnalysisBatchResult status();
}
