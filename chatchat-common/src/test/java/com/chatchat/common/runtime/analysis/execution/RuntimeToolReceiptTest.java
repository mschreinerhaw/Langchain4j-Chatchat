package com.chatchat.common.runtime.analysis.execution;

import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeToolReceiptTest {
    @Test void distinguishesExecutionStatesWithoutInventingRetryPermission() {
        assertThat(RuntimeToolReceipt.from(ToolOutput.success(List.of()), "SUCCESS"))
            .containsEntry("status", "EMPTY").doesNotContainKey("retryable");
        assertThat(RuntimeToolReceipt.from(ToolOutput.failure("blocked"), "MCP_POLICY_DENIED"))
            .containsEntry("status", "DENIED");
        assertThat(RuntimeToolReceipt.from(ToolOutput.failure("deadline elapsed"), "TIMEOUT"))
            .containsEntry("status", "TIMEOUT");
        assertThat(RuntimeToolReceipt.from(ToolOutput.failure("transport unavailable"), "FAILED"))
            .containsEntry("errorMessage", "transport unavailable");
        assertThat(RuntimeToolReceipt.from(null, null)).containsEntry("status", "OUTCOME_UNKNOWN");
    }
    @Test void retainsDeclaredCoverageAndRedactsCredentialFieldsInErrors() {
        var output = ToolOutput.failure("password=private token=private connection refused");
        output.setMetadata(Map.of("retryable", true, "truncated", true));
        assertThat(RuntimeToolReceipt.from(output, "FAILED"))
            .containsEntry("retryable", true).containsEntry("truncated", true);
        assertThat(RuntimeToolReceipt.from(output, "FAILED").get("errorMessage").toString())
            .doesNotContain("private").contains("connection refused");
    }
}
