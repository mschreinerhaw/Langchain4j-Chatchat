package com.chatchat.runtime.news.collector.service;

import com.chatchat.runtime.news.model.NewsCollectResult;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsCollectionTaskServiceTest {
    @Test void submissionDoesNotWaitForCollectionAndDeduplicatesActiveSource() {
        var collection = mock(NewsCollectionService.class);
        List<Runnable> pending = new ArrayList<>();
        var tasks = new NewsCollectionTaskService(collection, pending::add);
        var submitted = tasks.submit(7L);
        assertThat(submitted.status()).isEqualTo("QUEUED");
        assertThat(submitted.trigger()).isEqualTo("MANUAL");
        assertThat(tasks.submit(7L).executionId()).isEqualTo(submitted.executionId());
        assertThat(tasks.submitScheduled(7L).executionId()).isEqualTo(submitted.executionId());
        assertThat(pending).hasSize(1);
        verifyNoInteractions(collection);
        when(collection.collect(7L, submitted.executionId())).thenAnswer(invocation -> {
            assertThat(tasks.get(7L, submitted.executionId()).status()).isEqualTo("RUNNING");
            return new NewsCollectResult(submitted.executionId(), 7L, 9, 9, 0, 0, 0, null);
        });
        pending.get(0).run();
        var completed = tasks.get(7L, submitted.executionId());
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.result().acceptedCount()).isEqualTo(9);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(tasks.recent(10)).containsExactly(completed);
        assertThatThrownBy(() -> tasks.get(8L, submitted.executionId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.submit(7L).executionId()).isNotEqualTo(submitted.executionId());
    }

    @Test void scheduledSubmissionIsVisibleAndMarkedWithItsTrigger() {
        var collection = mock(NewsCollectionService.class);
        List<Runnable> pending = new ArrayList<>();
        var tasks = new NewsCollectionTaskService(collection, pending::add);

        var submitted = tasks.submitScheduled(11L);

        assertThat(submitted.status()).isEqualTo("QUEUED");
        assertThat(submitted.trigger()).isEqualTo("SCHEDULED");
        assertThat(tasks.recent(1)).containsExactly(submitted);
    }

    @Test void recordsCollectorFailureAndDoesNotReportItAsCommunicationFailure() {
        var collection = mock(NewsCollectionService.class);
        List<Runnable> pending = new ArrayList<>();
        var tasks = new NewsCollectionTaskService(collection, pending::add);
        when(collection.collect(anyLong(), anyString())).thenThrow(new IllegalStateException("source request failed"));
        var submitted = tasks.submit(3L);
        pending.get(0).run();
        assertThat(tasks.get(3L, submitted.executionId()).status()).isEqualTo("FAILED");
        assertThat(tasks.get(3L, submitted.executionId()).errorMessage()).isEqualTo("source request failed");
        reset(collection);
        when(collection.collect(anyLong(), anyString())).thenAnswer(i ->
            new NewsCollectResult(i.getArgument(1), 3L, 0, 0, 0, 0, 1, "robots denied"));
        submitted = tasks.submit(3L);
        pending.get(1).run();
        assertThat(tasks.get(3L, submitted.executionId()).status()).isEqualTo("FAILED");
        assertThat(tasks.get(3L, submitted.executionId()).result().failedCount()).isEqualTo(1);
    }

    @Test void rejectedSubmissionDoesNotLeavePhantomQueuedTask() {
        var collection = mock(NewsCollectionService.class);
        var tasks = new NewsCollectionTaskService(collection, command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        assertThatThrownBy(() -> tasks.submit(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tasks.submit(1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(collection);
    }

    @Test void temporalExecutionRunsOnCallerThreadAndPropagatesFailureForRetry() {
        var collection = mock(NewsCollectionService.class);
        var tasks = new NewsCollectionTaskService(collection, Runnable::run);
        when(collection.collect(7L, "temporal:workflow:run")).thenReturn(
            new NewsCollectResult("temporal:workflow:run", 7L, 3, 2, 0, 0, 1, "one item failed"),
            new NewsCollectResult("temporal:workflow:run", 7L, 3, 3, 0, 0, 0, null));

        assertThatThrownBy(() -> tasks.executeScheduled(7L, "temporal:workflow:run"))
            .isInstanceOf(IllegalStateException.class).hasMessage("one item failed");
        assertThat(tasks.get(7L, "temporal:workflow:run").status()).isEqualTo("FAILED");

        var retried = tasks.executeScheduled(7L, "temporal:workflow:run");
        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(retried.trigger()).isEqualTo("SCHEDULED");
        verify(collection, times(2)).collect(7L, "temporal:workflow:run");
    }
}
