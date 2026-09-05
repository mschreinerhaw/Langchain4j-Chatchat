package com.chatchat.agents.orchestration.model;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BoundedModelCallTest {
    @Test void providerWaitIsBounded() {
        assertThatThrownBy(() -> BoundedModelCall.call(() -> {
            try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return "";
        }, 30, () -> {})).isInstanceOf(BoundedModelCall.LimitExceeded.class);
    }
    @Test void cancellationCannotBeMistakenForProviderFailure() {
        assertThatThrownBy(() -> BoundedModelCall.call(() -> "unused", 1000,
            () -> { throw new CancellationException(); })).isInstanceOf(CancellationException.class);
        assertThat(BoundedModelCall.call(() -> "result", 1000, () -> {})).isEqualTo("result");
    }
}
