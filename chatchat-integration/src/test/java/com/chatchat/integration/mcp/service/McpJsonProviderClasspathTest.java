package com.chatchat.integration.mcp.service;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonProviderClasspathTest {

    @Test
    void usesOnlyTheJackson2ProviderRequiredByTheSpringBootRuntime() throws Exception {
        List<String> providers = ServiceLoader.load(McpJsonMapperSupplier.class).stream()
            .map(provider -> provider.type().getName())
            .toList();

        assertThat(providers)
            .containsExactly("io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier");
        assertThat(McpJsonDefaults.getMapper().writeValueAsString(java.util.Map.of("status", "ok")))
            .isEqualTo("{\"status\":\"ok\"}");
    }
}
