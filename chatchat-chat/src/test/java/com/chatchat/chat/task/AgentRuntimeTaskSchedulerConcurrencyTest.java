package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeTaskSchedulerConcurrencyTest {

    @Test
    void coalescesConcurrentScansAndAllowsTheNextScanAfterCompletion() throws Exception {
        AgentScheduledTaskService scheduledTaskService = mock(AgentScheduledTaskService.class);
        AgentTaskService taskService = mock(AgentTaskService.class);
        CountDownLatch firstScanEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstScan = new CountDownLatch(1);
        when(scheduledTaskService.scanDueTasks()).thenAnswer(invocation -> {
            firstScanEntered.countDown();
            assertThat(releaseFirstScan.await(5, TimeUnit.SECONDS)).isTrue();
            return 1;
        });
        AgentRuntimeTaskScheduler scheduler = new AgentRuntimeTaskScheduler(scheduledTaskService, taskService);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(scheduler::scanDueTasks);
            assertThat(firstScanEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> concurrent = executor.submit(scheduler::scanDueTasks);
            concurrent.get(5, TimeUnit.SECONDS);
            verify(scheduledTaskService, times(1)).scanDueTasks();

            releaseFirstScan.countDown();
            first.get(5, TimeUnit.SECONDS);

            releaseFirstScan.countDown();
            scheduler.scanDueTasks();
            verify(scheduledTaskService, times(2)).scanDueTasks();
        } finally {
            releaseFirstScan.countDown();
            executor.shutdownNow();
        }
    }
}
