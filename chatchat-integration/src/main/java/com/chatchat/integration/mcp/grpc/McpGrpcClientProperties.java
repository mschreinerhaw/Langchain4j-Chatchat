package com.chatchat.integration.mcp.grpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.grpc.client")
public class McpGrpcClientProperties {
    private boolean enabled = true;
    private String host = "localhost";
    private int port = 9091;
    private boolean plaintext = true;
    private String trustCertificatePath;
    private long deadlineMs = 1_800_000;
    private int maxInboundMessageBytes = 4 * 1024 * 1024;
    private long maxResponseBytes = 512L * 1024 * 1024;

    public int resolvedPort() { return Math.max(1, Math.min(65_535, port)); }
    public long resolvedDeadlineMs() { return Math.max(1_000, deadlineMs); }
    public int resolvedMaxInboundMessageBytes() { return Math.max(1024 * 1024, maxInboundMessageBytes); }
    public long resolvedMaxResponseBytes() { return Math.max(1024 * 1024, maxResponseBytes); }
}
