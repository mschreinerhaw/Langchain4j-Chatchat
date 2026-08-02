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

    @Test
    void malformedStructuredPayloadNeverBecomesAvailableEvidence() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, "{\"success\":true,\"rows\":[")
        ));

        assertThat(result.availability()).isEqualTo(McpResultEvidencePolicy.Availability.UNAVAILABLE);
        assertThat(result.resultAvailable()).isFalse();
    }

    @Test
    void structuredWebResultWithFalseEmptyMarkerRemainsAvailable() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, """
                {"success":true,"empty_result":false,
                 "results":[{"title":"market observation","retrievalSource":"tencent_wsa"}],
                 "financialData":[{"dataset":"runtime_dataset","count":1,"rows":[{"value":123}]}]}
                """)
        ));

        assertThat(result.availability()).isEqualTo(McpResultEvidencePolicy.Availability.AVAILABLE);
        assertThat(result.resultAvailable()).isTrue();
    }

    @Test
    void transportErrorTextMislabelledAsSuccessNeverBecomesEvidence() {
        for (String output : List.of(
            "timeout while reading upstream response",
            "Error: provider rejected request",
            "<html><title>502 Bad Gateway</title><body>error</body></html>"
        )) {
            McpResultEvidencePolicy.Assessment result = policy.assess(List.of(trace(true, output)));
            assertThat(result.availability()).as(output)
                .isEqualTo(McpResultEvidencePolicy.Availability.UNAVAILABLE);
            assertThat(result.resultAvailable()).as(output).isFalse();
        }
    }

    @Test
    void mixedAvailableAndFailedToolsArePartialInsteadOfGloballyUnavailable() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(
            trace(true, "{\"success\":true,\"results\":[{\"title\":\"usable evidence\"}]}"),
            trace(false, "timeout")
        ));

        assertThat(result.availability()).isEqualTo(McpResultEvidencePolicy.Availability.PARTIAL);
        assertThat(result.resultAvailable()).isTrue();
        assertThat(result.totalToolCount()).isEqualTo(2);
        assertThat(result.successfulToolCount()).isEqualTo(1);
        assertThat(result.failedToolCount()).isEqualTo(1);
        assertThat(result.availableResultCount()).isEqualTo(1);
        assertThat(result.unavailableResultCount()).isEqualTo(1);
    }

    @Test
    void historicalJavaMapRenderingWithNonEmptyResultsRemainsAvailable() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(trace(true,
            "{results=[{title=market evidence, url=https://example.test/news}], count=1}")));

        assertThat(result.availability()).isEqualTo(McpResultEvidencePolicy.Availability.AVAILABLE);
        assertThat(result.availableResultCount()).isEqualTo(1);
    }

    @Test
    void historicalJavaMapRenderingWithExplicitEmptyResultsRemainsEmpty() {
        McpResultEvidencePolicy.Assessment result = policy.assess(List.of(trace(true,
            "{results=[], count=0}")));

        assertThat(result.availability()).isEqualTo(McpResultEvidencePolicy.Availability.EMPTY);
        assertThat(result.emptyResultCount()).isEqualTo(1);
    }

    private InteractionToolTrace trace(boolean success, String output) {
        return InteractionToolTrace.builder()
            .toolName("mcp_query")
            .success(success)
            .output(output)
            .build();
    }
}
