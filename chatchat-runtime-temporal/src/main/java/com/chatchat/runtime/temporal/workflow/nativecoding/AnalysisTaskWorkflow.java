package com.chatchat.runtime.temporal.workflow.nativecoding;
import com.chatchat.runtime.temporal.contract.nativecoding.*;
import io.temporal.workflow.*;
@WorkflowInterface
public interface AnalysisTaskWorkflow {
    @WorkflowMethod(name="AnalysisTaskWorkflow.v1") NativeAnalysisResult execute(NativeAnalysisReference reference);
    @SignalMethod void progress(NativeAnalysisProgress progress);
    @QueryMethod NativeAnalysisProgress status();
}
