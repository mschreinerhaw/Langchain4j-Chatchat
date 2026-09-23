package com.chatchat.common.runtime.analysis.workflow;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

public interface AnalysisRuntimePort extends RuntimeProtocolPort {
    AnalysisExecutionOutcome analyze(AnalysisContext context);
}
