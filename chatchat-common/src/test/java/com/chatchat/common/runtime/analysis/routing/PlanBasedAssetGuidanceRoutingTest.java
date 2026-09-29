package com.chatchat.common.runtime.analysis.routing;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.capability.ProblemAnalysisPlan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PlanBasedAssetGuidanceRoutingTest {
    private AnalysisContext context(String query) {
        return new AnalysisContext(query, KernelDataScope.system("r"), "agent", List.of(), List.of(), List.of(), null, Map.of());
    }
    @Test void businessPhrasesNeverSelectAssetGuidanceWithoutAPlan() {
        for (String query : List.of("净值比对分析主要分析哪些内容，适用什么场景", "这个API怎么用", "分析客户换手率用什么数据"))
            assertThat(new StandardAnalysisQueryAnalyzer().analyze(context(query)).requiredCapabilities())
                .doesNotContain(AnalysisCapability.ASSET_GUIDANCE);
    }
    @Test void planSelectsGuidanceRegardlessOfBusinessVocabulary() {
        var plan = new ProblemAnalysisPlan(ProblemAnalysisPlan.Status.READY, "解释用途", "对象", "任意领域", "需要元数据",
            List.of(new ProblemAnalysisPlan.Task("解释契约", ProblemAnalysisPlan.Intent.ASSET_USAGE_GUIDANCE, List.of("元数据"), "用途说明")), "");
        assertThat(new StandardAnalysisQueryAnalyzer().analyze(context("X-42").withAttribute("problemAnalysisPlan", plan))
            .requiredCapabilities()).containsExactly(AnalysisCapability.ASSET_GUIDANCE);
    }
    @Test void explicitExecutionIntentIsNeverReplacedByPhraseMatching() {
        var intent = new AnalysisIntent("ANALYSIS", List.of(), Set.of(AnalysisCapability.STRUCTURED_DATA), "UNSPECIFIED", true);
        assertThat(new StandardAnalysisQueryAnalyzer().analyze(context("这个 table 如何使用").withIntent(intent))).isSameAs(intent);
        assertThat(new StandardAnalysisQueryAnalyzer().analyze(context("API用途").withAttribute("requiredCapabilities", List.of("TOOL_CALL")))
            .requiredCapabilities()).containsExactly(AnalysisCapability.TOOL_CALL);
    }
}
