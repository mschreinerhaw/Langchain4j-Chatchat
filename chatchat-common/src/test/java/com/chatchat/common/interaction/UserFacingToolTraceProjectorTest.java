package com.chatchat.common.interaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingToolTraceProjectorTest {

    @Test
    void removesBackendInputsOutputsAndAnalysisMetadata() {
        InteractionToolTrace backendTrace = InteractionToolTrace.builder()
            .toolName("mcp_chatchat_mcp_server_linux_command_execute")
            .displayName("linux_command_execute")
            .serviceId("chatchat-mcp-server")
            .success(true)
            .input(Map.of("command", "docker images"))
            .output("{\"schemaVersion\":\"tool_execution_result.v1\",\"stdout\":\"raw output\"}")
            .durationMs(804L)
            .runtimeMetadata(Map.of("analysisRecords", List.of(Map.of("content", "raw output"))))
            .build();

        assertThat(UserFacingToolTraceProjector.project(List.of(backendTrace)))
            .singleElement()
            .satisfies(receipt -> {
                assertThat(receipt.getToolName()).isEqualTo(backendTrace.getToolName());
                assertThat(receipt.isSuccess()).isTrue();
                assertThat(receipt.getDurationMs()).isEqualTo(804L);
                assertThat(receipt.getInput()).isEmpty();
                assertThat(receipt.getOutput()).isNull();
                assertThat(receipt.getRuntimeMetadata()).isEmpty();
            });

        assertThat(backendTrace.getOutput()).contains("raw output");
        assertThat(backendTrace.getRuntimeMetadata()).containsKey("analysisRecords");
    }
}
