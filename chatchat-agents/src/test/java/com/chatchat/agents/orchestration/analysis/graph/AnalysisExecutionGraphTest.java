package com.chatchat.agents.orchestration.analysis.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;
import static com.chatchat.agents.orchestration.analysis.graph.AnalysisExecutionGraph.*;

class AnalysisExecutionGraphTest {
    @Test void clarificationShortCircuitsEveryDownstreamNode() {
        AtomicInteger calls = new AtomicInteger();
        Result result = new AnalysisExecutionGraph().execute(List.of(
            new Step("preflight", () -> Status.NEEDS_CLARIFICATION),
            new Step("model", () -> { calls.incrementAndGet(); return Status.COMPLETED; })), null);
        assertThat(result.status()).isEqualTo(Status.NEEDS_CLARIFICATION);
        assertThat(calls).hasValue(0);
        assertThat(result.nodes()).hasSize(1);
    }
    @Test void limitationsAreTerminalWithoutAnImplicitRetry() {
        AtomicInteger calls = new AtomicInteger();
        Result result = new AnalysisExecutionGraph().execute(List.of(
            new Step("preflight", () -> Status.READY),
            new Step("judge", () -> { calls.incrementAndGet(); return Status.COMPLETED_WITH_LIMITATIONS; }),
            new Step("unexpected_retry", () -> { throw new AssertionError("Must not execute"); })), null);
        assertThat(result.status()).isEqualTo(Status.COMPLETED_WITH_LIMITATIONS);
        assertThat(calls).hasValue(1);
        assertThat(result.nodes()).hasSize(2);
    }
    @Test void transportFailureIsPreservedAndDoesNotReachPublication() {
        IllegalStateException failure = new IllegalStateException("transport unavailable");
        assertThatThrownBy(() -> new AnalysisExecutionGraph().execute(List.of(
            new Step("model", () -> { throw failure; }),
            new Step("publish", () -> { throw new AssertionError("Must not publish"); })), null))
            .isSameAs(failure);
    }
    @Test void cancellationStopsBeforeAnyNodeWork() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> new AnalysisExecutionGraph().execute(List.of(
            new Step("model", () -> { calls.incrementAndGet(); return Status.COMPLETED; })),
            () -> { throw new CancellationException("cancelled"); }))
            .isInstanceOf(CancellationException.class);
        assertThat(calls).hasValue(0);
    }
    @Test void readyIsNotACompletionStatus() {
        assertThatThrownBy(() -> new AnalysisExecutionGraph().execute(List.of(
            new Step("preflight", () -> Status.READY)), null))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("terminal disposition");
    }
}
