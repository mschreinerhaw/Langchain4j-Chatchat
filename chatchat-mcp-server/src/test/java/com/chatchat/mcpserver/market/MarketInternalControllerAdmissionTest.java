package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.model.MarketObservation;
import com.chatchat.runtime.market.storage.FinancialDataIngestionService;
import com.chatchat.runtime.market.storage.FinancialDataStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class MarketInternalControllerAdmissionTest {
    final FinancialDataIngestionService ingestion = mock(FinancialDataIngestionService.class);
    final MarketInternalController controller = new MarketInternalController(ingestion);
    final MarketObservation observation = mock(MarketObservation.class);
    final FinancialDataStore.StoredObservation stored = mock(FinancialDataStore.StoredObservation.class);

    @Test void concurrentIngestionIsRejectedWithoutWaitingForTheWriter() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(ingestion.accept(observation)).thenAnswer(call -> {
            entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return stored;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> controller.ingest(observation));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                assertThatThrownBy(() -> controller.ingest(observation))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode().value()).isEqualTo(503)));
            verify(ingestion, times(1)).accept(observation);
            release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS).getData()).isSameAs(stored);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void failedIngestionReleasesAdmissionForTheNextObservation() {
        when(ingestion.accept(observation)).thenThrow(new IllegalStateException("writer failed")).thenReturn(stored);
        assertThatThrownBy(() -> controller.ingest(observation)).isInstanceOf(IllegalStateException.class);
        assertThat(controller.ingest(observation).getData()).isSameAs(stored);
    }

    @Test void rejectedDatasetDoesNotHoldAdmissionOrAcknowledgeStorage() {
        when(ingestion.accept(observation)).thenReturn(null, stored);
        assertThatThrownBy(() -> controller.ingest(observation)).isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.ingest(observation).getData()).isSameAs(stored);
    }
}
