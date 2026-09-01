package com.chatchat.runtime.news.search;

import com.chatchat.common.tool.ToolInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSearchQueryResolverTest {

    @Test
    void usesAnalyzedKeywordsAndDropsTheOriginalQuestion() {
        var resolved = ExternalSearchQueryResolver.resolve(ToolInput.builder().parameters(Map.of(
            "query", "请帮我分析一下最近人工智能行业发生了什么以及未来会怎么样",
            "queryTerms", List.of("人工智能行业动态", "AI 产业趋势",
                "请帮我分析一下最近人工智能行业发生了什么以及未来会怎么样")
        )).build());

        assertThat(resolved.query()).isEqualTo("人工智能行业动态 AI 产业趋势");
        assertThat(resolved.source()).isEqualTo("analyzed_keywords");
        assertThat(resolved.terms()).doesNotContain("请帮我分析一下最近人工智能行业发生了什么以及未来会怎么样");
    }

    @Test
    void fallsBackToAnalyzedIntentButNeverToTheOriginalQuestion() {
        var intent = ExternalSearchQueryResolver.resolve(ToolInput.builder().parameters(Map.of(
            "query", "请帮我看看这个事情现在到底怎么样了",
            "intent", "新能源汽车价格战最新进展"
        )).build());
        var missing = ExternalSearchQueryResolver.resolve(ToolInput.builder().parameters(Map.of(
            "query", "请帮我看看这个事情现在到底怎么样了"
        )).build());

        assertThat(intent.query()).isEqualTo("新能源汽车价格战最新进展");
        assertThat(intent.source()).isEqualTo("analyzed_intent");
        assertThat(missing.query()).isBlank();
        assertThat(missing.source()).isEqualTo("missing_analyzed_query");
    }
}
