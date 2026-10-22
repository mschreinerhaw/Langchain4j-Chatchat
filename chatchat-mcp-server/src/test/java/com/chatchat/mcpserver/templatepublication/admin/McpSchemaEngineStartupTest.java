package com.chatchat.mcpserver.templatepublication.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exercise the real SDK bootstrap, not a mocked publisher or an isolated JPA context. */
class McpSchemaEngineStartupTest {
    @Test void sdkAndReleaseSchemasUseTheSameCompatibleEngine() throws Exception {
        var json=new ObjectMapper();
        var transport=HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(json)).mcpEndpoint("/mcp").build();
        var server=McpServer.sync(transport).serverInfo("unified-startup-test","2.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build()).build();
        try {
            var schema=json.readTree("{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"integer\"}},\"required\":[\"value\"]}");
            ToolReleaseSchemas.validate(schema,json.readTree("{\"value\":1}"));
            assertThatThrownBy(()->ToolReleaseSchemas.validate(schema,json.readTree("{\"value\":\"bad\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        } finally { server.close(); }
    }
}
