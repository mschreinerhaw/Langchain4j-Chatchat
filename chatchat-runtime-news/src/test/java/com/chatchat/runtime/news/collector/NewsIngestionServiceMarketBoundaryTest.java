package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.RawNewsItem;
import com.chatchat.runtime.news.normalize.NewsNormalizer;
import com.chatchat.runtime.news.source.NewsCollectRecordRepository;
import com.chatchat.runtime.news.store.NewsBulkIndexer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsIngestionServiceMarketBoundaryTest {
    @Test
    void forwardsMarketObservationWithoutWritingNewsIndex() {
        NewsNormalizer normalizer = mock(NewsNormalizer.class);
        NewsBulkIndexer bulkIndexer = mock(NewsBulkIndexer.class);
        McpMarketIngestionClient market = mock(McpMarketIngestionClient.class);
        RawNewsItem observation = mock(RawNewsItem.class);
        when(market.accepts(observation)).thenReturn(true);
        NewsIngestionService service = new NewsIngestionService(normalizer,
            mock(NewsCollectRecordRepository.class), bulkIndexer,
            mock(NewsAttachmentIngestionService.class), market);

        NewsAcceptance result = service.accept(observation);

        assertThat(result).isEqualTo(NewsAcceptance.ACCEPTED);
        verify(market).accept(observation);
        verify(normalizer, never()).normalize(observation);
        verify(bulkIndexer, never()).submit(org.mockito.ArgumentMatchers.any());
    }
}
