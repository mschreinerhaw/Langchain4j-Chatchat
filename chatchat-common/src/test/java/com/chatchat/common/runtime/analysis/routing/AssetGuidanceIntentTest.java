package com.chatchat.common.runtime.analysis.routing;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AssetGuidanceIntentTest {
    @Test void recognizesGuidanceAndReverseAssetQuestions() {
        for (String query : List.of("这个 API 是做什么用的", "这个表现在用于哪些业务场景，使用效果如何", "customer_trade_detail 这个表怎么用", "如何调用这个接口", "分析客户换手率用什么数据", "What is this table used for?"))
            assertThat(AssetGuidanceIntent.matches(query)).as(query).isTrue();
    }
    @Test void doesNotTurnAnalysisOrActionsIntoGuidance() {
        for (String query : List.of("帮我分析这个客户", "计算本月指标", "帮我执行这个 API", "这个表怎么用，再帮我执行一下", "这个制度怎么规定", "查询数据表里的交易数据"))
            assertThat(AssetGuidanceIntent.matches(query)).as(query).isFalse();
    }
    @Test void guidanceOverridesKeywordBasedDataAndToolRoutingButNotExplicitIntent() {
        var context = new AnalysisContext("这个 table 如何调用，使用效果如何", KernelDataScope.system("r"), "agent",
            List.of("bound-doc"), List.of(), List.of(), null, Map.of());
        var analyzer = new StandardAnalysisQueryAnalyzer();
        assertThat(analyzer.analyze(context).requiredCapabilities()).containsExactly(AnalysisCapability.ASSET_GUIDANCE);
        assertThat(analyzer.analyze(context.withAttribute("requiredCapabilities", List.of("TOOL_CALL"))).requiredCapabilities())
            .containsExactly(AnalysisCapability.TOOL_CALL);
    }
}
