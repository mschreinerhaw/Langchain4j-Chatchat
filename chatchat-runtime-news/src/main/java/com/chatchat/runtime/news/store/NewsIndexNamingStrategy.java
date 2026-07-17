package com.chatchat.runtime.news.store;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

@Component
public class NewsIndexNamingStrategy {
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final NewsRuntimeProperties.OpenSearch properties;
    private final Clock clock;

    @Autowired
    public NewsIndexNamingStrategy(NewsRuntimeProperties properties) {
        this(properties.getOpenSearch(), Clock.system(ZoneId.of(properties.getOpenSearch().getZoneId())));
    }

    NewsIndexNamingStrategy(NewsRuntimeProperties.OpenSearch properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String writeIndex(Instant collectTime) {
        Instant timestamp = collectTime == null ? clock.instant() : collectTime;
        return properties.getIndexName() + "-" + SUFFIX.format(timestamp.atZone(clock.getZone()));
    }

    public List<String> readableIndices() {
        LocalDate today = LocalDate.now(clock);
        return IntStream.range(0, retentionDays())
            .mapToObj(offset -> properties.getIndexName() + "-" + SUFFIX.format(today.minusDays(offset)))
            .toList();
    }

    public String indexPattern() {
        return properties.getIndexName() + "-*";
    }

    public Optional<LocalDate> dateOf(String indexName) {
        String prefix = properties.getIndexName() + "-";
        if (indexName == null || !indexName.startsWith(prefix)) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(indexName.substring(prefix.length()), SUFFIX));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public boolean isExpired(String indexName) {
        LocalDate oldestRetainedDate = LocalDate.now(clock).minusDays(retentionDays() - 1L);
        return dateOf(indexName).map(date -> date.isBefore(oldestRetainedDate)).orElse(false);
    }

    private int retentionDays() {
        return Math.max(1, properties.getRetentionDays());
    }
}
