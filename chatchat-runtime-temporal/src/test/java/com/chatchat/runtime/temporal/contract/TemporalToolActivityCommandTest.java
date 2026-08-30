package com.chatchat.runtime.temporal.contract;

import com.chatchat.agents.runtime.tool.ToolActivityRetryPolicy;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.common.tool.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalToolActivityCommandTest {

    private final ToolRuntimeRequest request = ToolRuntimeRequest.builder()
        .toolName("customer_trade_query")
        .build();

    @Test
    void clampsUnadmittedToolToOneAttempt() {
        TemporalToolActivityCommand command = new TemporalToolActivityCommand(
            request, "tenant:run:step", 5, 60, false, "not proven idempotent");

        assertThat(command.maximumAttempts()).isEqualTo(1);
    }

    @Test
    void derivesBoundedAttemptsFromGovernedToolMetadata() {
        ToolMetadata metadata = ToolMetadata.builder()
            .operationType("read")
            .metadata(Map.of(
                ToolActivityRetryPolicy.RETRY_SAFE_METADATA, true,
                ToolActivityRetryPolicy.MAXIMUM_ATTEMPTS_METADATA, 3))
            .build();

        TemporalToolActivityCommand command = TemporalToolActivityCommand.governed(
            request, metadata, "tenant:run:step", 60);

        assertThat(command.retrySafe()).isTrue();
        assertThat(command.maximumAttempts()).isEqualTo(3);
    }
}
