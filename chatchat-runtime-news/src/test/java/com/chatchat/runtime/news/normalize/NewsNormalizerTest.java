package com.chatchat.runtime.news.normalize;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsNormalizerTest {
    @Test
    void allowsSourceSpecificMinimumForShortTelegraphsWithoutChangingContent() {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.setMinimumContentChars(80);
        NewsNormalizer normalizer = new NewsNormalizer(properties);
        NewsSource source = new NewsSource(2L, "short", "短电报", NewsSourceType.CLS_TELEGRAPH,
            "https://www.cls.cn/telegraph", "cls.cn", Map.of(), Map.of("minimumContentChars", 1), true);

        var document = normalizer.normalize(new RawNewsItem(source, "停牌提醒", "今日停牌。",
            null, "财联社", "https://www.cls.cn/detail/2", Instant.now(), "zh-CN",
            List.of("财联社电报"), List.of(), Map.of()));

        assertThat(document.content()).isEqualTo("今日停牌。");
    }

    @Test
    void convertsHtmlIntoModelReadyPlainText() {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.setMinimumContentChars(10);
        NewsNormalizer normalizer = new NewsNormalizer(properties);
        NewsSource source = new NewsSource(1L, "demo", "Demo", NewsSourceType.RSS,
            "https://example.com/rss", "example.com", Map.of(), Map.of(), true);

        var document = normalizer.normalize(new RawNewsItem(source, "<h1>Important&nbsp;News</h1>",
            "<article><p>First paragraph.</p><script>bad()</script><p>Second paragraph.</p></article>",
            "<b>Summary</b>", "Author", "https://EXAMPLE.com/news/1#tracking", Instant.now(),
            "en", List.of("finance", "finance"), List.of(), Map.of()));

        assertThat(document.title()).isEqualTo("Important News");
        assertThat(document.content()).contains("First paragraph.", "Second paragraph.").doesNotContain("<article>");
        assertThat(document.sourceUrl()).isEqualTo("https://example.com/news/1");
        assertThat(document.categories()).containsExactly("finance");
    }
}
