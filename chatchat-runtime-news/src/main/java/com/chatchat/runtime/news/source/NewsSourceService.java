package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NewsSourceService {

    private final NewsSourceRepository sourceRepository;
    private final NewsSourceRuleRepository ruleRepository;
    private final ObjectMapper objectMapper;

    public NewsSourceService(NewsSourceRepository sourceRepository,
                             NewsSourceRuleRepository ruleRepository,
                             ObjectMapper objectMapper) {
        this.sourceRepository = sourceRepository;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
    }

    public NewsSource requireEnabled(Long sourceId) {
        NewsSource source = require(sourceId);
        if (!source.enabled()) {
            throw new IllegalStateException("News source is disabled: " + sourceId);
        }
        return source;
    }

    public NewsSource require(Long sourceId) {
        NewsSourceEntity entity = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("News source does not exist: " + sourceId));
        Map<String, String> selectors = new LinkedHashMap<>();
        ruleRepository.findBySourceId(sourceId).ifPresent(rule -> {
            put(selectors, "listSelector", rule.getListSelector());
            put(selectors, "linkSelector", rule.getLinkSelector());
            put(selectors, "titleSelector", rule.getTitleSelector());
            put(selectors, "contentSelector", rule.getContentSelector());
            put(selectors, "authorSelector", rule.getAuthorSelector());
            put(selectors, "publishTimeSelector", rule.getPublishTimeSelector());
            put(selectors, "urlPattern", rule.getUrlPattern());
        });
        NewsSource source = new NewsSource(
            entity.getId(), entity.getSourceCode(), entity.getSourceName(), entity.getSourceType(),
            entity.getEntryUrl(), entity.getAllowedDomain(), Map.copyOf(selectors),
            configuration(entity.getConfigurationJson()), entity.isEnabled()
        );
        validate(source);
        return source;
    }

    public void markCollected(Long sourceId, String cursor) {
        NewsSourceEntity entity = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("News source does not exist: " + sourceId));
        entity.setLastCollectedAt(java.time.Instant.now());
        entity.setLastCursor(cursor);
        entity.setUpdatedAt(java.time.Instant.now());
        sourceRepository.save(entity);
    }

    public String lastCursor(Long sourceId) {
        NewsSourceEntity entity = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("News source does not exist: " + sourceId));
        return entity.getLastCursor();
    }

    private Map<String, Object> configuration(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid news source configuration JSON", ex);
        }
    }

    private void validate(NewsSource source) {
        URI entry = URI.create(source.entryUrl());
        if (!("http".equalsIgnoreCase(entry.getScheme()) || "https".equalsIgnoreCase(entry.getScheme()))) {
            throw new IllegalArgumentException("News source entryUrl must use HTTP or HTTPS");
        }
        if (source.sourceType() == NewsSourceType.WEB_LIST) {
            requireSelector(source, "linkSelector");
            requireSelector(source, "titleSelector");
            requireSelector(source, "contentSelector");
            if (source.allowedDomain() == null || source.allowedDomain().isBlank()) {
                throw new IllegalArgumentException("WEB_LIST news source requires allowedDomain");
            }
        }
        if (source.sourceType() == NewsSourceType.WEB_SINGLE_PAGE) {
            requireSelector(source, "titleSelector");
            requireSelector(source, "contentSelector");
        }
    }

    private void requireSelector(NewsSource source, String key) {
        if (!source.selectors().containsKey(key)) {
            throw new IllegalArgumentException(source.sourceType() + " news source requires " + key);
        }
    }

    private void put(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }
}
