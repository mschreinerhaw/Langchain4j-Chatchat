package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

public interface AnalysisRuntimePort extends RuntimeProtocolPort {
    AnalysisExecutionOutcome analyze(AnalysisContext context);
}
