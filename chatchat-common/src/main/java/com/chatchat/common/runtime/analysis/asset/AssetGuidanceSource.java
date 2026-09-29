package com.chatchat.common.runtime.analysis.asset;

import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import java.util.List;

/** Implementations must use caller/Agent-authorized metadata retrieval, never template execution. */
public interface AssetGuidanceSource {
    Result retrieve(AnalysisContext context);
    default Result acquireMetadata(AnalysisContext context, GuidanceDataRequestPlan plan) {
        return new Result(List.of(), List.of("当前未配置资产目录元数据获取能力。"), false);
    }
    record Result(List<AssetContext> assets, List<String> limitations, boolean hasMore) {
        public Result {
            assets = List.copyOf(assets);
            limitations = List.copyOf(limitations);
        }
    }
}
