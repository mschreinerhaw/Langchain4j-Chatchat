package com.chatchat.mcpserver.authorization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpScopeExpressionTest {

    @Test
    void parsesAndMatchesRequestedScope() {
        McpScopeExpression scope = McpScopeExpression.parse(
            "mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read"
        );

        assertThat(scope.assetType()).isEqualTo("api_service");
        assertThat(scope.capability()).isEqualTo("asset");
        assertThat(scope.matches("api_service", "asset", "query", "tenant-a")).isTrue();
        assertThat(scope.matches("ssh_host", "asset", "query", "tenant-a")).isFalse();
        assertThat(scope.matches("api_service", "asset", "query", "tenant-b")).isFalse();
    }
}
