package com.chatchat.runtime.news.analysis;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsDocument;
import com.chatchat.runtime.news.model.NewsSourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsContentAnalyzerTest {

    @Test
    void generatesSummaryCategoriesAndTagsWithoutExternalModel() {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getAnalysis().setSummaryMaxChars(90);
        NewsContentAnalyzer analyzer = new NewsContentAnalyzer(properties);
        NewsDocument source = document("某上市公司发布董事会公告，披露2026年上半年业绩增长。"
            + "公司股票代码为600001。A股市场成交额同步放大，投资者应关注后续公告。"
            + "本段用于确保正文长度足以触发摘要截断并验证摘要不会复制全部正文内容。"
            + "更多经营数据将在定期报告中披露。");

        NewsDocument analyzed = analyzer.analyze(source);

        assertThat(analyzed.analysisStatus()).isEqualTo(NewsAnalysisStatus.COMPLETED);
        assertThat(analyzed.summary()).isNotBlank().hasSizeLessThan(source.content().length());
        assertThat(analyzed.categories()).contains("上市公司", "市场动态");
        assertThat(analyzed.tags()).contains("上市公司", "A股", "600001");
    }

    private NewsDocument document(String content) {
        return new NewsDocument("doc-1", 1L, "测试资讯源", NewsSourceType.WEB_LIST,
            "上市公司公告", content, null, null, "https://example.com/1", Instant.now(), Instant.now(),
            "zh-CN", List.of(), List.of(), "hash", NewsAnalysisStatus.PENDING, Map.of());
    }
}
