package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.common.runtime.capability.WorkflowOutcome;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InteractionExecutionTest {
    private final WorkflowEntryPlan entry = WorkflowEntryPlan.of(WorkflowEntryPlan.Owner.GOVERNED_RUNTIME, "CONTRACT");

    @Test void cannotCompleteInsideAnActiveChildOrAfterAChildFailure() {
        var execution = new InteractionExecution(InteractionRequest.builder().build());
        assertThatThrownBy(() -> execution.call("CHILD", () -> execution.complete(success(), entry)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.complete(success(), entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test void lateResultAfterCancellationCannotBecomeSuccess() {
        var cancelled = new AtomicBoolean();
        var request = InteractionRequest.builder().toolInput(Map.of("__agentCancellation", (BooleanSupplier) cancelled::get)).build();
        var execution = new InteractionExecution(request);
        assertThatThrownBy(() -> execution.call("MODEL", () -> { cancelled.set(true); return success(); }))
            .isInstanceOf(CancellationException.class);
        assertThatThrownBy(() -> execution.complete(success(), entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test void wrappedInterruptRemainsCancellationAndPreservesThreadFlag() {
        var execution = new InteractionExecution(InteractionRequest.builder().build());
        try {
            assertThatThrownBy(() -> execution.call("MODEL", () -> { throw new IllegalStateException(new InterruptedException()); }))
                .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void nativeResultUsesItsEvaluationWithoutAFabricatedProblemPlan() {
        var execution = new InteractionExecution(InteractionRequest.builder().build());
        var response = execution.complete(execution.call("GOVERNED_RUNTIME", this::success), entry);
        assertThat(response.getMetadata()).containsEntry("runtimeLifecycle", List.of("GOVERNED_RUNTIME", "EVALUATE", "COMPLETE"))
            .doesNotContainKeys("problemAnalysisPlan", "capabilityPlan", "workflowFamily");
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).publicStatus()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> execution.complete(response, entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unverifiedTextDoesNotBecomeSuccessAtTheCommonBoundary() {
        var execution = new InteractionExecution(InteractionRequest.builder().build());
        var response = execution.complete(InteractionResponse.builder().answer("claimed success").build(), entry);
        assertThat(((WorkflowOutcome) response.getMetadata().get(WorkflowOutcome.METADATA_KEY)).type())
            .isEqualTo(WorkflowOutcome.Type.INSUFFICIENT_EVIDENCE);
    }

    private InteractionResponse success() {
        return InteractionResponse.builder().answer("verified").metadata(Map.of("agent", Map.of("publicStatus", "SUCCESS"))).build();
    }
}
