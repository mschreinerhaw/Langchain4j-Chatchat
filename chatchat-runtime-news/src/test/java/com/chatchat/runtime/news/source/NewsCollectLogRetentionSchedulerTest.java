package com.chatchat.runtime.news.source;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsCollectLogRetentionSchedulerTest {

    @Test
    void defaultsToSevenDaysAndContinuesUntilTheLastPartialBatch() {
        NewsCollectLogRetentionService service = mock(NewsCollectLogRetentionService.class);
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.getCollectLog().setCleanupBatchSize(2);
        when(service.deleteExpiredBatch(any(Instant.class), eq(2))).thenReturn(2, 1);
        Instant before = Instant.now().minus(7, ChronoUnit.DAYS).minusSeconds(1);

        new NewsCollectLogRetentionScheduler(service, properties).cleanup();

        Instant after = Instant.now().minus(7, ChronoUnit.DAYS).plusSeconds(1);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(service, times(2)).deleteExpiredBatch(cutoff.capture(), eq(2));
        assertThat(cutoff.getAllValues()).allSatisfy(value -> assertThat(value).isBetween(before, after));
    }
}
