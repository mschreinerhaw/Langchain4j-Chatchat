package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFairExecutorTest {

    @Test
    void enforcesTenantConcurrencyAndLetsAnotherTenantProgress() throws Exception {
        var delegate = Executors.newFixedThreadPool(3);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxConcurrentPerTenant(1);
        properties.setMaxQueuedPerTenant(10);
        properties.setQueueCapacity(20);
        TenantFairExecutor executor = new TenantFairExecutor(delegate, properties);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        AtomicInteger activeA = new AtomicInteger();
        AtomicInteger peakA = new AtomicInteger();
        List<String> order = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                executor.execute("tenant-a", () -> {
                    int active = activeA.incrementAndGet();
                    peakA.accumulateAndGet(active, Math::max);
                    order.add("a");
                    try { releaseA.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        activeA.decrementAndGet();
                        finished.countDown();
                    }
                });
            }
            executor.execute("tenant-b", () -> { order.add("b"); finished.countDown(); });
            await(() -> order.contains("b"));
            assertThat(order).contains("b");
            releaseA.countDown();
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(peakA).hasValue(1);
        } finally {
            releaseA.countDown();
            delegate.shutdownNow();
        }
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
    }
}
