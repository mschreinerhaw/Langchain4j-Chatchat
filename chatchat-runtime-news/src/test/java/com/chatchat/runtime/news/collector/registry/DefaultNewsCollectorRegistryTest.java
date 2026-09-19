package com.chatchat.runtime.news.collector.registry;

import com.chatchat.runtime.news.collector.NewsCollector;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultNewsCollectorRegistryTest {
    @Test
    void selectsHighestPriorityCollectorForSourceType() {
        NewsCollector fallback = collector("fallback", 0, NewsSourceType.API);
        NewsCollector custom = collector("custom", 100, NewsSourceType.API);
        var registry = new DefaultNewsCollectorRegistry(List.of(fallback, custom));

        assertThat(registry.require(source(NewsSourceType.API, Map.of()))).isSameAs(custom);
    }

    @Test
    void explicitCollectorIdAllowsExtensionWithoutAddingSourceType() {
        NewsCollector custom = collector("partner-feed", 0);
        var registry = new DefaultNewsCollectorRegistry(List.of(custom));

        NewsSource source = source(NewsSourceType.API, Map.of("collectorId", "partner-feed"));

        assertThat(registry.require(source)).isSameAs(custom);
    }

    @Test
    void rejectsDuplicateIdsAndAmbiguousPriorities() {
        assertThatThrownBy(() -> new DefaultNewsCollectorRegistry(List.of(
            collector("duplicate", 0, NewsSourceType.API),
            collector("duplicate", 1, NewsSourceType.RSS))))
            .hasMessageContaining("Duplicate news collector id");

        var ambiguous = new DefaultNewsCollectorRegistry(List.of(
            collector("first", 10, NewsSourceType.API),
            collector("second", 10, NewsSourceType.API)));
        assertThatThrownBy(() -> ambiguous.require(source(NewsSourceType.API, Map.of())))
            .hasMessageContaining("Ambiguous news collectors");
    }

    private NewsCollector collector(String id, int priority, NewsSourceType... sourceTypes) {
        List<NewsSourceType> supported = List.of(sourceTypes);
        return new NewsCollector() {
            @Override public boolean supports(NewsSourceType sourceType) { return supported.contains(sourceType); }
            @Override public NewsCollectResult collect(NewsSource source, NewsCollectContext context) { return null; }
            @Override public String collectorId() { return id; }
            @Override public int priority() { return priority; }
        };
    }

    private NewsSource source(NewsSourceType type, Map<String, Object> configuration) {
        return new NewsSource(1L, "source", "Source", type, "https://example.test", "example.test",
            Map.of(), configuration, true);
    }
}
