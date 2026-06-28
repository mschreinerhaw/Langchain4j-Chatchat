package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpScopeExpressionTest {

    @Test
    void serializesAndParsesScopeExpression() {
        McpScopeExpression scope = McpScopeExpression.of(
            "API_SERVICE",
            "asset",
            "query",
            "tenant-a",
            "order",
            "READ"
        );

        assertThat(scope.value()).isEqualTo("mcp:api_service:asset:query@tenant=tenant-a;domain=order;level=read");
        assertThat(McpScopeExpression.parse(scope.value())).isEqualTo(scope);
    }
}
