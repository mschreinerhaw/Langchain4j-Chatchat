package com.chatchat.common.runtime.analysis.asset;

import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import java.util.List;

/** Suggestions are not evidence of actual adoption, performance or quality. */
public interface AssetGuidanceEnhancer {
    Recommendation recommend(AnalysisContext context, AssetContext asset);
    record Recommendation(String advice, List<String> skillIds, String status) {
        public Recommendation { skillIds = List.copyOf(skillIds); }
    }
}
