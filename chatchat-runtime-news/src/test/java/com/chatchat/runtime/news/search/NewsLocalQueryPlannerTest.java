package com.chatchat.runtime.news.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsLocalQueryPlannerTest {

    @Test
    void extractsBoundedKeywordsFromNaturalChineseSearchRequest() {
        NewsLocalQueryPlanner.QueryPlan plan = NewsLocalQueryPlanner.plan(
            "请搜索最近关于英伟达、微软和 OpenAI 的新闻", null, 8);

        assertThat(plan.keywords()).containsExactly("英伟达", "微软", "OpenAI");
        assertThat(plan.queries()).containsExactly(
            "请搜索最近关于英伟达、微软和 OpenAI 的新闻", "英伟达", "微软", "OpenAI");
    }

    @Test
    void explicitTermsAreDeduplicatedAndBounded() {
        NewsLocalQueryPlanner.QueryPlan plan = NewsLocalQueryPlanner.plan(
            "market overview", List.of("chips", "AI", "chips", "compute", "cloud"), 3);

        assertThat(plan.keywords()).containsExactly("chips", "AI", "compute");
        assertThat(plan.queries()).containsExactly("market overview", "chips", "AI", "compute");
    }
}
