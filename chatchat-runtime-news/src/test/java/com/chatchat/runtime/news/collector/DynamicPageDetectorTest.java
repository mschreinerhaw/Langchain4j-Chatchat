package com.chatchat.runtime.news.collector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPageDetectorTest {

    private final DynamicPageDetector detector = new DynamicPageDetector();

    @Test
    void identifiesVueAndNextJsShells() {
        assertThat(detector.inspect("<div id='app'><li v-for='item in rows'>{{item.title}}</li></div>"))
            .satisfies(result -> {
                assertThat(result.dynamic()).isTrue();
                assertThat(result.indicators()).contains("unrendered-template-expression", "vue-template-markers");
            });
        assertThat(detector.inspect("<div id='__next'></div><script id='__NEXT_DATA__'>{}</script>"))
            .satisfies(result -> {
                assertThat(result.dynamic()).isTrue();
                assertThat(result.indicators()).contains("nextjs-markers", "empty-app-root");
            });
    }

    @Test
    void doesNotMisclassifyOrdinaryServerRenderedPage() {
        assertThat(detector.inspect("<main><a href='/news/1'>正常服务端新闻标题</a></main>").dynamic()).isFalse();
    }
}
