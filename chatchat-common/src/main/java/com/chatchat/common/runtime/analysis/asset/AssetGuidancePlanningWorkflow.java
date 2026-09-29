package com.chatchat.common.runtime.analysis.asset;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow;

/** Runtime drives these stages once in order. execute() only creates the understanding plan. */
public interface AssetGuidancePlanningWorkflow extends AnalysisWorkflow {
    AssetGuidanceSource.Result resolveTemplate(AnalysisContext context, AnalysisExecutionOutcome understanding);
    AnalysisExecutionOutcome requestData(AnalysisContext context, AnalysisExecutionOutcome understanding,
                                        AssetGuidanceSource.Result templates);
    AssetGuidanceSource.Result acquireData(AnalysisContext context, AnalysisExecutionOutcome request,
                                          AssetGuidanceSource.Result templates);
    AnalysisExecutionOutcome synthesize(AnalysisContext context, AnalysisExecutionOutcome request,
                                        AssetGuidanceSource.Result data);
}
