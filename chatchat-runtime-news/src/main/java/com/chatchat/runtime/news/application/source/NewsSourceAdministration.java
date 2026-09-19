package com.chatchat.runtime.news.application.source;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsCollectStatus;
import com.chatchat.runtime.news.model.NewsSourceType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Use cases exposed by the internal source-management API. */
public interface NewsSourceAdministration {
    List<NewsSourceView> list(Long capabilityId);

    NewsRecordPage records(Long sourceId, int page, int size);

    NewsSourceView create(Long capabilityId, NewsSourceUpsert request);

    NewsSourceView update(Long capabilityId, Long sourceId, NewsSourceUpsert request);

    void delete(Long capabilityId, Long sourceId);

    NewsRuleView saveRule(Long capabilityId, Long sourceId, NewsRuleUpsert request);

    NewsRuleView getRule(Long capabilityId, Long sourceId);

    record NewsSourceUpsert(String sourceCode, String sourceName, NewsSourceType sourceType, String entryUrl,
                            String collectionDescription, String allowedDomain, String scheduleCron,
                            Boolean enabled, Map<String, Object> configuration) { }

    record NewsSourceView(Long id, Long capabilityId, String sourceCode, String sourceName,
                          NewsSourceType sourceType, String entryUrl, String collectionDescription,
                          String allowedDomain, String scheduleCron, boolean enabled,
                          Map<String, Object> configuration, Instant lastCollectedAt,
                          long collectedRecords, Instant updatedAt) { }

    record NewsRuleUpsert(String listSelector, String linkSelector, String titleSelector,
                          String contentSelector, String authorSelector, String publishTimeSelector,
                          String urlPattern) { }

    record NewsRuleView(Long id, Long sourceId, String listSelector, String linkSelector,
                        String titleSelector, String contentSelector, String authorSelector,
                        String publishTimeSelector, String urlPattern) { }

    record NewsRecordView(Long id, Long sourceId, String sourceName, String sourceUrl,
                          NewsCollectStatus collectStatus, NewsAnalysisStatus analysisStatus,
                          String documentId, Instant publishTime, Instant collectedAt,
                          String errorMessage) { }

    record NewsRecordPage(List<NewsRecordView> items, long total, int page, int size, int totalPages) { }
}
