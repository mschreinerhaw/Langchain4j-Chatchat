package com.chatchat.agents.runtime.tool;

import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolActivityRetryPolicyTest {

    private final ToolActivityRetryPolicy policy = new ToolActivityRetryPolicy();

    @Test
    void rejectsAutomaticRetryWithoutMetadata() {
        assertThat(policy.resolve(null))
            .isEqualTo(new ToolActivityRetryPolicy.Decision(
                false, 1, "tool metadata is unavailable"));
    }

    @Test
    void keepsWriteToolAtOneAttemptEvenWhenItClaimsIdempotency() {
        ToolMetadata metadata = ToolMetadata.builder()
            .operationType("write")
            .metadata(Map.of("idempotent", true, "workflowActivityMaximumAttempts", 4))
            .build();

        assertThat(policy.resolve(metadata).maximumAttempts()).isEqualTo(1);
    }

    @Test
    void requiresExplicitIdempotencyForReadOnlyTool() {
        ToolMetadata metadata = ToolMetadata.builder().operationType("read").build();

        assertThat(policy.resolve(metadata).maximumAttempts()).isEqualTo(1);
    }

    @Test
    void admitsBoundedRetryForExplicitlyIdempotentReadOnlyTool() {
        ToolMetadata metadata = ToolMetadata.builder()
            .operationType("read_only")
            .metadata(Map.of(
                ToolActivityRetryPolicy.RETRY_SAFE_METADATA, true,
                ToolActivityRetryPolicy.MAXIMUM_ATTEMPTS_METADATA, 9))
            .build();

        assertThat(policy.resolve(metadata))
            .isEqualTo(new ToolActivityRetryPolicy.Decision(
                true, 5, "explicitly idempotent read-only tool"));
    }
}
