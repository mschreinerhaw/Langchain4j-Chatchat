package com.chatchat.agents.orchestration.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelInvocationCapacityManagerTest {

    @Test
    void rejectsConcurrentWorkForTheSameModelWhenCapacityWaitExpires() {
        ModelInvocationCapacityManager capacity =
            new ModelInvocationCapacityManager(1, 1_000, 10);

        assertThatThrownBy(() -> capacity.invoke("shared-model",
            () -> capacity.invoke("shared-model", () -> "unreachable")))
            .isInstanceOf(ModelInvocationCapacityManager.ModelCapacityExceededException.class)
            .hasMessageContaining("MODEL_CONCURRENCY_CAPACITY_EXCEEDED")
            .hasMessageContaining("shared-model");
    }

    @Test
    void isolatesCapacityByResolvedModelIdentity() {
        ModelInvocationCapacityManager capacity =
            new ModelInvocationCapacityManager(1, 1_000, 50);

        String result = capacity.invoke("model-a",
            () -> capacity.invoke("model-b", () -> "completed"));

        assertThat(result).isEqualTo("completed");
    }

    @Test
    void rejectsRequestStartWhenPerModelRateWaitExceedsTheBound() {
        ModelInvocationCapacityManager capacity =
            new ModelInvocationCapacityManager(2, 1, 10);
        assertThat(capacity.invoke("rate-limited-model", () -> "first")).isEqualTo("first");

        assertThatThrownBy(() -> capacity.invoke("rate-limited-model", () -> "second"))
            .isInstanceOf(ModelInvocationCapacityManager.ModelCapacityExceededException.class)
            .hasMessageContaining("MODEL_RATE_CAPACITY_EXCEEDED");
    }
}
