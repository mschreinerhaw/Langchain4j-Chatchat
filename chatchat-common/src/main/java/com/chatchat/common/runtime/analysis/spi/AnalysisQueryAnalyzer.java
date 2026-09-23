package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;

@FunctionalInterface
public interface AnalysisQueryAnalyzer {
    AnalysisIntent analyze(AnalysisContext context);
}
