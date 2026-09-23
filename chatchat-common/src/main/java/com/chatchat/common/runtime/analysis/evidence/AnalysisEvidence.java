package com.chatchat.common.runtime.analysis.evidence;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;

import java.util.Map;

public interface AnalysisEvidence {
    String evidenceId();
    AnalysisCapability capability();
    String content();
    Map<String, Object> attributes();
}
