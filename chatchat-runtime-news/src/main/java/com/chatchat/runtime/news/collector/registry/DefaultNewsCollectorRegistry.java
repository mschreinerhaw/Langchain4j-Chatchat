package com.chatchat.runtime.news.collector.registry;

import com.chatchat.runtime.news.collector.NewsCollector;
import com.chatchat.runtime.news.collector.NewsCollectorDescriptor;
import com.chatchat.runtime.news.collector.NewsCollectorRegistry;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spring-backed registry. Adding a collector bean is sufficient for automatic discovery. */
@Component
public class DefaultNewsCollectorRegistry implements NewsCollectorRegistry {
    public static final String COLLECTOR_ID_CONFIGURATION_KEY = "collectorId";

    private final Map<String, NewsCollector> byId;
    private final Map<NewsSourceType, List<NewsCollector>> bySourceType;

    public DefaultNewsCollectorRegistry(List<NewsCollector> collectors) {
        Map<String, NewsCollector> ids = new LinkedHashMap<>();
        Map<NewsSourceType, List<NewsCollector>> types = new LinkedHashMap<>();
        for (NewsCollector collector : collectors) {
            NewsCollectorDescriptor descriptor = collector.descriptor();
            NewsCollector duplicate = ids.putIfAbsent(descriptor.collectorId(), collector);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate news collector id: " + descriptor.collectorId());
            }
            for (NewsSourceType sourceType : descriptor.sourceTypes()) {
                types.computeIfAbsent(sourceType, ignored -> new java.util.ArrayList<>()).add(collector);
            }
        }
        types.replaceAll((sourceType, candidates) -> candidates.stream()
            .sorted(Comparator.comparingInt((NewsCollector item) -> item.descriptor().priority()).reversed())
            .toList());
        this.byId = Map.copyOf(ids);
        this.bySourceType = Map.copyOf(types);
    }

    @Override
    public NewsCollector require(NewsSource source) {
        String configuredId = configuredCollectorId(source);
        if (configuredId != null) {
            return findById(configuredId).orElseThrow(() ->
                new IllegalStateException("Configured news collector does not exist: " + configuredId));
        }
        List<NewsCollector> candidates = bySourceType.getOrDefault(source.sourceType(), List.of());
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No collector for " + source.sourceType());
        }
        if (candidates.size() > 1
            && candidates.get(0).descriptor().priority() == candidates.get(1).descriptor().priority()) {
            throw new IllegalStateException("Ambiguous news collectors for " + source.sourceType()
                + ": " + candidates.get(0).descriptor().collectorId() + ", "
                + candidates.get(1).descriptor().collectorId());
        }
        return candidates.get(0);
    }

    @Override
    public Optional<NewsCollector> findById(String collectorId) {
        return Optional.ofNullable(collectorId == null ? null : byId.get(collectorId.trim()));
    }

    @Override
    public Collection<NewsCollectorDescriptor> descriptors() {
        return byId.values().stream().map(NewsCollector::descriptor).toList();
    }

    private String configuredCollectorId(NewsSource source) {
        if (source.configuration() == null) return null;
        Object value = source.configuration().get(COLLECTOR_ID_CONFIGURATION_KEY);
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.trim();
    }
}
