package com.chatchat.mcpserver.grpc;

import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.security.InternalCredentialProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Starts and gracefully stops the dedicated MCP Runtime gRPC server. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.mcp.grpc.server", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public final class McpGrpcServerLifecycle implements SmartLifecycle {
    private final McpRuntimeKernel kernel;
    private final ObjectMapper objectMapper;
    private final InternalCredentialProperties credentials;
    private final McpGrpcServerProperties properties;
    private volatile Server server;
    private volatile boolean running;

    public McpGrpcServerLifecycle(McpRuntimeKernel kernel, ObjectMapper objectMapper,
                                  InternalCredentialProperties credentials,
                                  McpGrpcServerProperties properties) {
        this.kernel = kernel;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Override public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        McpRuntimeGrpcService service = new McpRuntimeGrpcService(
            kernel, objectMapper, properties.resolvedChunkBytes());
        try {
            NettyServerBuilder builder = NettyServerBuilder.forPort(properties.resolvedPort())
                .maxInboundMessageSize(properties.resolvedMaxInboundMessageBytes())
                .permitKeepAliveWithoutCalls(true)
                .addService(ServerInterceptors.intercept(service,
                    new McpGrpcAuthorizationInterceptor(credentials)));
            if (!properties.isPlaintext()) {
                if (properties.getCertificateChainPath() == null || properties.getCertificateChainPath().isBlank()
                    || properties.getPrivateKeyPath() == null || properties.getPrivateKeyPath().isBlank()) {
                    throw new IllegalStateException("MCP gRPC TLS certificate and private key are required");
                }
                builder.useTransportSecurity(new java.io.File(properties.getCertificateChainPath()),
                    new java.io.File(properties.getPrivateKeyPath()));
            }
            server = builder.build().start();
            running = true;
            log.info("MCP Runtime gRPC server started port={} chunkBytes={}",
                properties.resolvedPort(), properties.resolvedChunkBytes());
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to start MCP Runtime gRPC server", failure);
        }
    }

    @Override public synchronized void stop() {
        Server current = server;
        running = false;
        if (current == null) return;
        current.shutdown();
        try { current.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!current.isTerminated()) current.shutdownNow();
        server = null;
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return false; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
