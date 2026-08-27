package com.chatchat.agents.orchestration.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisWorkerRetryPolicyTest {

    private final AnalysisWorkerRetryPolicy policy = new AnalysisWorkerRetryPolicy();

    @Test
    void performsOneInitialAttemptAndThreeRetries() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> failures = new ArrayList<>();

        String result = policy.execute(3, () -> false, attempt -> {
            if (calls.incrementAndGet() < 4) throw new IllegalStateException("transient");
            return "success";
        }, ignored -> { }, (attempt, failure) -> failures.add(attempt));

        assertThat(result).isEqualTo("success");
        assertThat(calls.get()).isEqualTo(4);
        assertThat(failures).containsExactly(1, 2, 3);
    }

    @Test
    void neverRetriesCancellation() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> policy.execute(3, () -> false, attempt -> {
            calls.incrementAndGet();
            throw new CancellationException("cancelled");
        }, ignored -> { }, (attempt, failure) -> { }))
            .isInstanceOf(CancellationException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
