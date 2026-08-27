package com.chatchat.agents.orchestration.analysis;

/** Driver-side sink for durable, user-visible Worker progress. */
@FunctionalInterface
public interface AnalysisTaskProgressListener {

    AnalysisTaskProgressListener NOOP = progress -> { };

    void onProgress(AnalysisTaskProgress progress);
}
