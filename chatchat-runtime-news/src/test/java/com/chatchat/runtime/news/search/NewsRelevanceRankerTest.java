package com.chatchat.runtime.news.search;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRelevanceRankerTest {

    @Test
    void ranksRelevantNewsAndFiltersGenericHighVolumeNoise() {
        String query = "A股主要指数 2026年8月14日 行情数据 成交量";
        List<NewsDocument> candidates = List.of(
            document("bond", "债券托管统计月报", "债券发行和托管数量统计", List.of("债券", "统计")),
            document("collateral", "担保品业务规模", "保证金及质押业务数据", List.of("担保品", "数据")),
            document("quotes", "A股主要指数收盘行情", "沪深主要指数涨跌及市场成交量数据",
                List.of("A股", "指数", "行情", "成交量")));

        List<NewsRelevanceRanker.RankedNews> ranked = NewsRelevanceRanker.rank(query, candidates, 3);

        assertThat(ranked).extracting(item -> item.document().documentId())
            .startsWith("quotes")
            .doesNotContain("bond", "collateral");
        assertThat(ranked.get(0).score()).isPositive();
        assertThat(ranked.get(0).coverage()).isPositive();
    }

    @Test
    void worksForRuntimeVocabularyWithoutDomainRules() {
        List<NewsDocument> candidates = List.of(
            document("one", "采购公告", "办公设备采购", List.of("采购")),
            document("two", "Thermal sensor latency update", "Sensor response latency improved",
                List.of("thermal", "sensor", "latency")));

        assertThat(NewsRelevanceRanker.rank("thermal sensor latency trend", candidates, 1))
            .singleElement().satisfies(item ->
                assertThat(item.document().documentId()).isEqualTo("two"));
    }

    private NewsDocument document(String id, String title, String content, List<String> tags) {
        return new NewsDocument(id, 1L, "source", NewsSourceType.RSS, title, content, content, "author",
            "https://example.com/" + id, Instant.parse("2026-08-14T03:00:00Z"),
            Instant.parse("2026-08-14T03:01:00Z"), "zh-CN", List.of(), tags,
            "hash-" + id, NewsAnalysisStatus.COMPLETED, Map.of());
    }
}
