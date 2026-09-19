package com.chatchat.runtime.temporal.activity.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;
import com.chatchat.runtime.temporal.contract.analysis.TemporalAnalysisDatasetCommand;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Independent Activity boundary for one immutable business dataset. */
@ActivityInterface
public interface RuntimeOsAnalysisDatasetActivity {

    @ActivityMethod(name = "runtime-os-analysis-dataset-v1")
    AnalysisTaskResult execute(TemporalAnalysisDatasetCommand command);
}
