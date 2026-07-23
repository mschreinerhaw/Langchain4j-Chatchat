package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolNameResolverTest {
    private final AgentToolNameResolver resolver = new AgentToolNameResolver();

    @Test
    void normalizesMcpPrefixedWebSearchWhenAvailableToolsAreNotProvided() {
        assertThat(resolver.normalizeToolName(
            "mcp_chatchat_mcp_server_web_search", List.of()))
            .isEqualTo("web_search");
    }

    @Test
    void resolvesMcpPrefixedWebSearchToLocalRegisteredTool() {
        assertThat(resolver.normalizeToolName(
            "mcp_chatchat_mcp_server_web_search", List.of("web_search", "document_search")))
            .isEqualTo("web_search");
    }
}
