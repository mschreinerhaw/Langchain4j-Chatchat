package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public interface AnalysisEvidence {
    String evidenceId();
    AnalysisCapability capability();
    String content();
    Map<String, Object> attributes();
}
