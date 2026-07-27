package com.chatchat.api.controller;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSearchCancellationRegistryTest {

    @Test
    void identicalRequestIdsAreCancelledIndependentlyByTenant() throws Exception {
        DocumentSearchCancellationRegistry registry = new DocumentSearchCancellationRegistry();
        CountDownLatch registered = new CountDownLatch(2);
        AtomicBoolean tenantAInterrupted = new AtomicBoolean();
        AtomicBoolean tenantBInterrupted = new AtomicBoolean();
        Thread tenantA = searchThread(registry, "tenant-a", "same-request", registered, tenantAInterrupted);
        Thread tenantB = searchThread(registry, "tenant-b", "same-request", registered, tenantBInterrupted);

        tenantA.start();
        tenantB.start();
        assertThat(registered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.activeCount("tenant-a")).isEqualTo(1);
        assertThat(registry.activeCount("tenant-b")).isEqualTo(1);

        assertThat(registry.cancel("tenant-a", "same-request")).isTrue();
        tenantA.join(2_000);

        assertThat(tenantAInterrupted).isTrue();
        assertThat(tenantBInterrupted).isFalse();
        assertThat(tenantB.isAlive()).isTrue();
        assertThat(registry.activeCount("tenant-a")).isZero();
        assertThat(registry.activeCount("tenant-b")).isEqualTo(1);

        assertThat(registry.cancel("tenant-b", "same-request")).isTrue();
        tenantB.join(2_000);
        assertThat(tenantBInterrupted).isTrue();
        assertThat(registry.activeCount("tenant-b")).isZero();
    }

    private Thread searchThread(DocumentSearchCancellationRegistry registry,
                                String tenantId,
                                String requestId,
                                CountDownLatch registered,
                                AtomicBoolean interrupted) {
        return new Thread(() -> {
            registry.register(tenantId, requestId);
            registered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                interrupted.set(true);
            } finally {
                registry.complete(tenantId, requestId);
            }
        });
    }
}
