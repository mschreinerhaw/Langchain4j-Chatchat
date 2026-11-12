package com.chatchat.runtime.temporal.activity.nativecoding;
import com.chatchat.runtime.temporal.contract.nativecoding.*;

/** Existing Native execution and governance boundary, not an analysis planner. */
public interface NativeAnalysisPort {
    @FunctionalInterface interface Checkpoint { void pulse(NativeAnalysisProgress progress); }
    NativeAnalysisResult execute(NativeAnalysisReference reference,String workflowId,String attemptId,int attempt,Checkpoint heartbeat);
    NativeAnalysisResult stop(NativeAnalysisReference reference,String reason);
}
