package com.chatchat.mcpserver.external;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.common.security.InternalSecretCipher;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StreamableHttpMcpWorkflow implements ExternalMcpExecutionWorkflow {
    private static final int MAX_TOOLS = 500;
    private final InternalCredentialProperties credentials;

    @Override public String id() { return "mcp_streamable_http"; }

    @Override public List<McpSchema.Tool> discover(ExternalMcpService service) {
        try (McpSyncClient client = connect(service)) {
            List<McpSchema.Tool> tools = new ArrayList<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = client.listTools(cursor);
                tools.addAll(page.tools());
                if (tools.size() > MAX_TOOLS) throw new IllegalArgumentException("External MCP exposes too many tools");
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
            return List.copyOf(tools);
        }
    }

    @Override public McpSchema.CallToolResult invoke(ExternalMcpService service, String toolName,
                                                       Map<String, Object> arguments) {
        try (McpSyncClient client = connect(service)) {
            return client.callTool(new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments));
        }
    }

    private McpSyncClient connect(ExternalMcpService service) {
        URI uri = URI.create(service.getEndpoint());
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (service.getAuthorization() != null && !service.getAuthorization().isBlank()) {
            request.header("Authorization", InternalSecretCipher.decryptIfNecessary(
                service.getAuthorization(), credentials.resolvedSecret()));
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(origin)
            .endpoint(path)
            .requestBuilder(request)
            .connectTimeout(Duration.ofSeconds(10))
            .maxResponseSize(2 * 1024 * 1024)
            .build();
        McpSyncClient client = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .initializationTimeout(Duration.ofSeconds(10))
            .build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }
}
