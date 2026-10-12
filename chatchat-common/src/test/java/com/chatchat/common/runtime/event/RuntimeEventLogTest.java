package com.chatchat.common.runtime.event;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEventLogTest {
    @Test void recordsNavigationAndExecutionFactsWithoutRawCredentialsOrModelContent() {
        String line = RuntimeEventLog.format(event(Map.of("metadata", Map.of(
            "eventKind", "HARNESS_TURN", "eventState", "COMPLETED", "turn", 2,
            "reads", java.util.List.of(Map.of("status", "ACCEPTED", "request", Map.of(
                "operation", "READ_TEXT", "datasetReference", "ref-1", "fromChar", 3000,
                "parameters", Map.of("password", "private-password")), "receiptFingerprint", "sha-1")),
            "workspace", Map.of("notes", "private reasoning"), "prompt", "private prompt",
            "content", "raw stdout", "authorization", "private-token"))));
        assertThat(line).contains("run-1", "event-1", "\"sequence\":3", "HARNESS_TURN", "READ_TEXT", "ref-1", "3000", "sha-1")
            .doesNotContain("private-password", "private reasoning", "private prompt", "raw stdout", "private-token");
    }

    @Test void redactsDiagnosticSecretsAndEscapesInjectedLogLines() {
        String line = RuntimeEventLog.format(event(Map.of("errorMessage",
            "failed password=secret-one token:secret-two Authorization: Bearer secret-three https://root:secret-four@host\nFAKE_EVENT", "errorCode", "TOOL_TIMEOUT")));
        assertThat(line).contains("TOOL_TIMEOUT", "[redacted]", "\\u000a")
            .doesNotContain("secret-one", "secret-two", "secret-three", "secret-four", "\n");
    }

    @Test void boundsLargeOrCyclicPayloadAndMarksProjectionWithoutChangingOriginal() {
        Map<String,Object> payload = new java.util.LinkedHashMap<>();
        payload.put("reads", Collections.nCopies(2000, Map.of("errorMessage", "x".repeat(10000))));
        payload.put("metadata", payload);
        String line = RuntimeEventLog.format(event(payload));
        assertThat(line.length()).isLessThan(12000);
        assertThat(line).contains("\"logProjectionTruncated\":true");
        assertThat(((java.util.List<?>) payload.get("reads")).size()).isEqualTo(2000);
    }

    private RuntimeEvent event(Map<String,Object> payload) {
        return new RuntimeEvent() {
            public String eventId() { return "event-1"; }
            public String streamId() { return "run-1"; }
            public String eventType() { return "OBSERVATION_RECORDED"; }
            public long occurredAt() { return 123L; }
            public long sequence() { return 3L; }
            public Map<String,Object> payload() { return payload; }
        };
    }
}
