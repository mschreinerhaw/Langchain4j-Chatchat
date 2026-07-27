package com.chatchat.agents.assessment;

import com.chatchat.common.interaction.InteractionToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpResultEvidencePolicyTest {

    private final McpResultEvidencePolicy policy = new McpResultEvidencePolicy();

    @Test
    void marksSuccessfulNonEmptyRowsAsAvailable() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, """
                {"success":true,"rows":[{"tablespace":"USERS","size_mb":102400}],"rowCount":1}
                """)
        ));

        assertThat(result.availability())
            .isEqualTo(McpResultEvidencePolicy.Availability.AVAILABLE);
        assertThat(result.resultAvailable()).isTrue();
        assertThat(result.availableResultCount()).isEqualTo(1);
    }

    @Test
    void distinguishesSuccessfulEmptyQueryFromAvailableResult() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, """
                {"success":true,"rows":[],"rowCount":0}
                """)
        ));

        assertThat(result.availability())
            .isEqualTo(McpResultEvidencePolicy.Availability.EMPTY);
        assertThat(result.resultAvailable()).isFalse();
        assertThat(result.emptyResultCount()).isEqualTo(1);
    }

    @Test
    void failedToolOutputNeverBecomesAvailableEvidence() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(false, """
                {"error":"timeout","rows":[{"stale":"value"}]}
                """)
        ));

        assertThat(result.availability())
            .isEqualTo(McpResultEvidencePolicy.Availability.UNAVAILABLE);
        assertThat(result.resultAvailable()).isFalse();
    }

    @Test
    void acceptsDirectStructuredMetricsWithoutRowsWrapper() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, """
                {"tablespace":"USERS","size_mb":102400}
                """)
        ));

        assertThat(result.resultAvailable()).isTrue();
    }

    private InteractionToolTrace trace(boolean success, String output) {
        return InteractionToolTrace.builder()
            .toolName("mcp_query")
            .success(success)
            .output(output)
            .build();
    }
}
