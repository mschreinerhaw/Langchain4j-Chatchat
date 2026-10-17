package com.chatchat.mcpserver.mcp;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class McpInvocationContextTest {
    @Test void restoresEnvelopeForNestedAuthorizationAndReleasesItOnFailure() {
        assertThatThrownBy(() -> {
            try (var ignored = McpInvocationContext.openRequestMeta(Map.of(
                "user", Map.of("userId", "u1", "username", "operator"),
                "tenant", Map.of("tenantId", "t1")))) {
                assertThat(McpInvocationContext.current().userId()).isEqualTo("u1");
                assertThat(McpInvocationContext.current().tenantId()).isEqualTo("t1");
                throw new IllegalStateException("handler failed");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(McpInvocationContext.current()).isNull();
    }

    @Test void installedTransportPrincipalRemainsAuthoritativeIncludingAbsentFields() {
        var transport = new McpInvocationContext.Context(null, null, null, null, null,
            "real-user", null, null, null, null, null, null, null, null, null, null);
        try (var outer = McpInvocationContext.open(transport)) {
            try (var ignored = McpInvocationContext.openRequestMeta(Map.of(
                "user", Map.of("userId", "other-user"), "tenantId", "other-tenant"))) {
                assertThat(McpInvocationContext.current()).isSameAs(transport);
                assertThat(McpInvocationContext.current().tenantId()).isNull();
            }
            assertThat(McpInvocationContext.current()).isSameAs(transport);
        }
        assertThat(McpInvocationContext.current()).isNull();
    }

    @Test void absentEnvelopeDoesNotSupplyAnIdentity() {
        try (var ignored = McpInvocationContext.openRequestMeta(null)) {
            assertThat(McpInvocationContext.current().userId()).isNull();
            assertThat(McpInvocationContext.current().tenantId()).isNull();
        }
    }
}
