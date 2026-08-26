package com.chatchat.mcpserver.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.grpc.server")
public class McpGrpcServerProperties {
    private boolean enabled = true;
    private int port = 9091;
    private int chunkBytes = 1024 * 1024;
    private int maxInboundMessageBytes = 64 * 1024 * 1024;
    private boolean plaintext = true;
    private String certificateChainPath;
    private String privateKeyPath;

    public int resolvedPort() { return Math.max(1, Math.min(65_535, port)); }
    public int resolvedChunkBytes() { return Math.max(16 * 1024, Math.min(4 * 1024 * 1024, chunkBytes)); }
    public int resolvedMaxInboundMessageBytes() { return Math.max(1024 * 1024, maxInboundMessageBytes); }
}
