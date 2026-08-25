package com.chatchat.common.mcp.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpServiceContractTest {
    @Test
    void preservesRawResultAlongsideNormalizedData() {
        Map<String, Object> raw = Map.of("stdout", "container-a\ncontainer-b");
        McpServiceResult result = new McpServiceResult(null, "request-1", "docker", "ps",
            McpServiceResultStatus.SUCCESS, Map.of("containers", 2), raw,
            null, null, false, null, Map.of(), 0);

        assertThat(result.schemaVersion()).isEqualTo(McpServiceResult.SCHEMA_VERSION);
        assertThat(result.rawData()).isSameAs(raw);
        assertThat(result.successful()).isTrue();
    }

    @Test
    void queryMatchesLocalAndRemoteToolNames() {
        McpToolDescriptor tool = new McpToolDescriptor("docker", "docker_ps", "ps", "", "diagnostic",
            Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(new McpToolQuery("docker", "DIAGNOSTIC", Set.of("ps")).matches(tool)).isTrue();
        assertThat(new McpToolQuery("other", null, Set.of()).matches(tool)).isFalse();
    }

    @Test
    void rejectsUnknownProtocolVersions() {
        assertThatThrownBy(() -> new McpServiceCall("v999", null, "docker", "ps", Map.of(), Map.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
