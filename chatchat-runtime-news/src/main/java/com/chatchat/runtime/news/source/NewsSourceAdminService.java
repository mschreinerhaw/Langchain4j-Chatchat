package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsSourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class NewsSourceAdminService {
    private final NewsSourceRepository sourceRepository;
    private final NewsSourceRuleRepository ruleRepository;
    private final NewsCollectRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public NewsSourceAdminService(NewsSourceRepository sourceRepository, NewsSourceRuleRepository ruleRepository,
                                  NewsCollectRecordRepository recordRepository, ObjectMapper objectMapper) {
        this.sourceRepository = sourceRepository;
        this.ruleRepository = ruleRepository;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public List<NewsSourceView> list(Long capabilityId) {
        return sourceRepository.findByCapabilityIdOrderByUpdatedAtDesc(capabilityId).stream().map(this::view).toList();
    }

    public NewsRecordPage records(Long sourceId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        var result = sourceId == null
            ? recordRepository.findAllByOrderByIdDesc(PageRequest.of(safePage, safeSize))
            : recordRepository.findBySourceIdOrderByIdDesc(sourceId, PageRequest.of(safePage, safeSize));
        Map<Long, String> sourceNames = sourceRepository.findAllById(
                result.getContent().stream().map(NewsCollectRecordEntity::getSourceId).distinct().toList())
            .stream().collect(java.util.stream.Collectors.toMap(NewsSourceEntity::getId, NewsSourceEntity::getSourceName));
        List<NewsRecordView> items = result.getContent().stream().map(record -> new NewsRecordView(
            record.getId(), record.getSourceId(), sourceNames.getOrDefault(record.getSourceId(), "未知资讯源"),
            record.getSourceUrl(), record.getCollectStatus(), record.getAnalysisStatus(), record.getDocumentId(),
            record.getPublishTime(), record.getCollectedAt(), record.getErrorMessage())).toList();
        return new NewsRecordPage(items, result.getTotalElements(), safePage, safeSize, result.getTotalPages());
    }

    @Transactional
    public NewsSourceView create(Long capabilityId, NewsSourceUpsert request) {
        validate(request);
        if (sourceRepository.findBySourceCode(request.sourceCode().trim()).isPresent()) {
            throw new IllegalArgumentException("News source code already exists: " + request.sourceCode());
        }
        NewsSourceEntity entity = new NewsSourceEntity();
        entity.setCapabilityId(capabilityId);
        apply(entity, request);
        return view(sourceRepository.save(entity));
    }

    @Transactional
    public NewsSourceView update(Long capabilityId, Long sourceId, NewsSourceUpsert request) {
        validate(request);
        NewsSourceEntity entity = require(capabilityId, sourceId);
        sourceRepository.findBySourceCode(request.sourceCode().trim())
            .filter(other -> !other.getId().equals(sourceId))
            .ifPresent(other -> { throw new IllegalArgumentException("News source code already exists: " + request.sourceCode()); });
        apply(entity, request);
        return view(sourceRepository.save(entity));
    }

    @Transactional
    public void delete(Long capabilityId, Long sourceId) {
        NewsSourceEntity entity = require(capabilityId, sourceId);
        if (recordRepository.countBySourceId(sourceId) > 0) {
            throw new IllegalStateException("News source has collection records; disable it instead of deleting it");
        }
        ruleRepository.findBySourceId(sourceId).ifPresent(ruleRepository::delete);
        sourceRepository.delete(entity);
    }

    @Transactional
    public NewsRuleView saveRule(Long capabilityId, Long sourceId, NewsRuleUpsert request) {
        NewsSourceEntity source = require(capabilityId, sourceId);
        if (source.getSourceType() == NewsSourceType.WEB_LIST) {
            requireText(request.linkSelector(), "linkSelector");
            requireText(request.titleSelector(), "titleSelector");
            requireText(request.contentSelector(), "contentSelector");
        } else if (source.getSourceType() == NewsSourceType.WEB_SINGLE_PAGE) {
            requireText(request.titleSelector(), "titleSelector");
            requireText(request.contentSelector(), "contentSelector");
        }
        NewsSourceRuleEntity rule = ruleRepository.findBySourceId(sourceId).orElseGet(NewsSourceRuleEntity::new);
        rule.setSourceId(sourceId);
        rule.setListSelector(trim(request.listSelector()));
        rule.setLinkSelector(trim(request.linkSelector()));
        rule.setTitleSelector(trim(request.titleSelector()));
        rule.setContentSelector(trim(request.contentSelector()));
        rule.setAuthorSelector(trim(request.authorSelector()));
        rule.setPublishTimeSelector(trim(request.publishTimeSelector()));
        String urlPattern = trim(request.urlPattern());
        if (urlPattern != null) {
            try {
                Pattern.compile(urlPattern);
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("urlPattern is not a valid Java regular expression: " + ex.getDescription());
            }
        }
        rule.setUrlPattern(urlPattern);
        rule.setUpdatedAt(Instant.now());
        return ruleView(ruleRepository.save(rule));
    }

    public NewsRuleView getRule(Long capabilityId, Long sourceId) {
        require(capabilityId, sourceId);
        return ruleRepository.findBySourceId(sourceId).map(this::ruleView)
            .orElse(new NewsRuleView(null, sourceId, null, null, null, null, null, null, null));
    }

    private NewsSourceEntity require(Long capabilityId, Long sourceId) {
        NewsSourceEntity entity = sourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("News source does not exist: " + sourceId));
        if (!capabilityId.equals(entity.getCapabilityId())) {
            throw new IllegalArgumentException("News source does not belong to MCP capability: " + capabilityId);
        }
        return entity;
    }

    private void apply(NewsSourceEntity entity, NewsSourceUpsert request) {
        entity.setSourceCode(request.sourceCode().trim());
        entity.setSourceName(request.sourceName().trim());
        entity.setSourceType(request.sourceType());
        entity.setEntryUrl(request.entryUrl().trim());
        String collectionDescription = trim(request.collectionDescription());
        entity.setAllowedDomain(trim(request.allowedDomain()));
        entity.setScheduleCron(trim(request.scheduleCron()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        try {
            Map<String, Object> configuration = new LinkedHashMap<>(
                request.configuration() == null ? Map.of() : request.configuration());
            if (collectionDescription == null) {
                collectionDescription = trim(configuration.get("collectionDescription") instanceof String value ? value : null);
            }
            if (collectionDescription == null) configuration.remove("collectionDescription");
            else configuration.put("collectionDescription", collectionDescription);
            entity.setConfigurationJson(objectMapper.writeValueAsString(configuration));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid news source configuration", ex);
        }
        entity.setUpdatedAt(Instant.now());
    }

    private void validate(NewsSourceUpsert request) {
        if (request == null) throw new IllegalArgumentException("Request is required");
        requireText(request.sourceCode(), "sourceCode");
        requireText(request.sourceName(), "sourceName");
        requireText(request.entryUrl(), "entryUrl");
        if (request.sourceType() == null) throw new IllegalArgumentException("sourceType is required");
        if (request.collectionDescription() != null && request.collectionDescription().trim().length() > 2000) {
            throw new IllegalArgumentException("collectionDescription must not exceed 2000 characters");
        }
        URI uri = URI.create(request.entryUrl());
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("entryUrl must be an absolute HTTP(S) URL");
        }
        if (request.sourceType() == NewsSourceType.WEB_LIST) requireText(request.allowedDomain(), "allowedDomain");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private NewsSourceView view(NewsSourceEntity entity) {
        Map<String, Object> configuration = Map.of();
        try {
            if (entity.getConfigurationJson() != null && !entity.getConfigurationJson().isBlank()) {
                configuration = objectMapper.readValue(entity.getConfigurationJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            }
        } catch (Exception ignored) { }
        String collectionDescription = trim(configuration.get("collectionDescription") instanceof String value ? value : null);
        return new NewsSourceView(entity.getId(), entity.getCapabilityId(), entity.getSourceCode(), entity.getSourceName(),
            entity.getSourceType(), entity.getEntryUrl(), collectionDescription, entity.getAllowedDomain(), entity.getScheduleCron(), entity.isEnabled(),
            configuration, entity.getLastCollectedAt(), recordRepository.countBySourceId(entity.getId()), entity.getUpdatedAt());
    }

    private NewsRuleView ruleView(NewsSourceRuleEntity rule) {
        return new NewsRuleView(rule.getId(), rule.getSourceId(), rule.getListSelector(), rule.getLinkSelector(),
            rule.getTitleSelector(), rule.getContentSelector(), rule.getAuthorSelector(), rule.getPublishTimeSelector(), rule.getUrlPattern());
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record NewsSourceUpsert(String sourceCode, String sourceName, NewsSourceType sourceType, String entryUrl,
                                   String collectionDescription,
                                   String allowedDomain, String scheduleCron, Boolean enabled, Map<String, Object> configuration) { }

    public record NewsSourceView(Long id, Long capabilityId, String sourceCode, String sourceName, NewsSourceType sourceType,
                                 String entryUrl, String collectionDescription, String allowedDomain, String scheduleCron, boolean enabled,
                                 Map<String, Object> configuration, Instant lastCollectedAt, long collectedRecords, Instant updatedAt) { }

    public record NewsRuleUpsert(String listSelector, String linkSelector, String titleSelector, String contentSelector,
                                 String authorSelector, String publishTimeSelector, String urlPattern) { }

    public record NewsRuleView(Long id, Long sourceId, String listSelector, String linkSelector, String titleSelector,
                               String contentSelector, String authorSelector, String publishTimeSelector, String urlPattern) { }

    public record NewsRecordView(Long id, Long sourceId, String sourceName, String sourceUrl,
                                 com.chatchat.runtime.news.model.NewsCollectStatus collectStatus,
                                 com.chatchat.runtime.news.model.NewsAnalysisStatus analysisStatus,
                                 String documentId, Instant publishTime, Instant collectedAt, String errorMessage) { }

    public record NewsRecordPage(List<NewsRecordView> items, long total, int page, int size, int totalPages) { }
}
