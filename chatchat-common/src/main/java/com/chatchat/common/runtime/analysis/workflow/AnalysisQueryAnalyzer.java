package com.chatchat.common.runtime.analysis.workflow;

@FunctionalInterface
public interface AnalysisQueryAnalyzer {
    AnalysisIntent analyze(AnalysisContext context);
}
