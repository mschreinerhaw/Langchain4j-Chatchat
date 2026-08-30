package com.chatchat.runtime.temporal.activity;

import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOsPlanStageActivityImplTest {

    @Test
    void missingBusinessHandlerFailsClosedWithoutRetry() {
        RuntimeOsPlanStageActivityImpl activity = new RuntimeOsPlanStageActivityImpl(null);

        assertThatThrownBy(() -> activity.arbitrate((PlanModelArbitrationCommand) null))
            .isInstanceOfSatisfying(ApplicationFailure.class, failure -> {
                org.assertj.core.api.Assertions.assertThat(failure.getType())
                    .isEqualTo("PLAN_PHASE_HANDLER_UNAVAILABLE");
                org.assertj.core.api.Assertions.assertThat(failure.isNonRetryable()).isTrue();
            });
    }
}
