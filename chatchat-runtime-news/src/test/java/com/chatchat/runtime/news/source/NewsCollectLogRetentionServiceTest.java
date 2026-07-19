package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.model.NewsAnalysisStatus;
import com.chatchat.runtime.news.model.NewsCollectStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class NewsCollectLogRetentionServiceTest {

    @Autowired
    private NewsCollectRecordRepository repository;

    @Test
    void deletesOnlyRecordsOlderThanTheSevenDayCutoff() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        NewsCollectRecordEntity expired = repository.save(record("expired-hash", now.minus(8, ChronoUnit.DAYS)));
        NewsCollectRecordEntity retained = repository.save(record("retained-hash", now.minus(6, ChronoUnit.DAYS)));

        int deleted = new NewsCollectLogRetentionService(repository)
            .deleteExpiredBatch(now.minus(7, ChronoUnit.DAYS), 1_000);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findById(expired.getId())).isEmpty();
        assertThat(repository.findById(retained.getId())).isPresent();
    }

    private NewsCollectRecordEntity record(String urlHash, Instant collectedAt) {
        NewsCollectRecordEntity record = new NewsCollectRecordEntity();
        record.setSourceId(1L);
        record.setSourceUrl("https://example.com/" + urlHash);
        record.setUrlHash(urlHash);
        record.setCollectStatus(NewsCollectStatus.INDEXED);
        record.setAnalysisStatus(NewsAnalysisStatus.COMPLETED);
        record.setCollectedAt(collectedAt);
        return record;
    }
}
