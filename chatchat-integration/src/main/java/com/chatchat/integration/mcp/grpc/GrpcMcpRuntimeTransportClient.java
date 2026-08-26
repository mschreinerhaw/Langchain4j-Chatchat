package com.chatchat.integration.mcp.grpc;

import com.chatchat.common.kernel.KernelHealth;
import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcp.grpc.McpGrpcPayloads;
import com.chatchat.mcp.grpc.v1.JsonRequest;
import com.chatchat.mcp.grpc.v1.McpRuntimeServiceGrpc;
import com.chatchat.mcp.grpc.v1.PayloadChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** gRPC client adapter used by chatchat-api for all MCP Runtime OS operations. */
@Component("mcpRuntimeTransportPort")
@ConditionalOnProperty(prefix = "chatchat.mcp.grpc.client", name = "enabled",
    havingValue = "true", matchIfMissing = true)
public class GrpcMcpRuntimeTransportClient implements McpRuntimeTransportPort {
    private final ObjectMapper objectMapper;
    private final McpGrpcClientProperties properties;
    private final ManagedChannel channel;
    private final McpRuntimeServiceGrpc.McpRuntimeServiceBlockingStub stub;

    public GrpcMcpRuntimeTransportClient(ObjectMapper objectMapper,
                                         McpGrpcClientProperties properties,
                                         InternalCredentialProperties credentials) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        NettyChannelBuilder builder = NettyChannelBuilder
            .forAddress(properties.getHost(), properties.resolvedPort())
            .maxInboundMessageSize(properties.resolvedMaxInboundMessageBytes());
        if (properties.isPlaintext()) {
            builder.usePlaintext();
        } else if (properties.getTrustCertificatePath() != null
            && !properties.getTrustCertificatePath().isBlank()) {
            try {
                builder.sslContext(GrpcSslContexts.forClient()
                    .trustManager(new java.io.File(properties.getTrustCertificatePath())).build());
            } catch (javax.net.ssl.SSLException failure) {
                throw new IllegalStateException("Invalid MCP gRPC trust certificate", failure);
            }
        } else {
            builder.useTransportSecurity();
        }
        this.channel = builder.build();
        this.stub = McpRuntimeServiceGrpc.newBlockingStub(channel)
            .withCallCredentials(new BearerCredentials(credentials));
    }

    @Override public List<McpServiceDescriptor> services() {
        return decode(call(null, stub()::services), new TypeReference<>() { });
    }

    @Override public List<McpToolDescriptor> tools(McpToolQuery query) {
        return decode(call(query == null ? McpToolQuery.all() : query, stub()::tools), new TypeReference<>() { });
    }

    @Override public McpServiceResult invoke(McpServiceCall call) {
        return decode(call(call, stub()::invoke), McpServiceResult.class);
    }

    @Override public McpResultRepairResult repair(McpResultRepairRequest request) {
        return decode(call(request, stub()::repair), McpResultRepairResult.class);
    }

    @Override public void refresh() { call(null, stub()::refresh); }

    @Override public List<McpDomainContractDescriptor> contracts() {
        return decode(call(null, stub()::contracts), new TypeReference<>() { });
    }

    @Override public McpContractAuditReport audit(McpContractAuditRequest request) {
        return decode(call(request, stub()::audit), McpContractAuditReport.class);
    }

    @Override public KernelHealth health() {
        return decode(call(null, stub()::health), KernelHealth.class);
    }

    private McpRuntimeServiceGrpc.McpRuntimeServiceBlockingStub stub() {
        return stub.withDeadlineAfter(properties.resolvedDeadlineMs(), TimeUnit.MILLISECONDS)
            .withCompression("gzip");
    }

    private byte[] call(Object payload, Function<JsonRequest, Iterator<PayloadChunk>> operation) {
        try {
            String requestId = UUID.randomUUID().toString();
            byte[] json = objectMapper.writeValueAsBytes(payload == null ? java.util.Map.of() : payload);
            JsonRequest request = JsonRequest.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION)
                .setRequestId(requestId)
                .setPayloadJson(ByteString.copyFrom(json))
                .build();
            return McpGrpcPayloads.assemble(operation.apply(request),
                properties.resolvedMaxResponseBytes(), requestId);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("MCP gRPC request failed", failure);
        }
    }

    private <T> T decode(byte[] payload, Class<T> type) {
        try { return objectMapper.readValue(payload, type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid MCP gRPC response", failure); }
    }

    private <T> T decode(byte[] payload, TypeReference<T> type) {
        try { return objectMapper.readValue(payload, type); }
        catch (Exception failure) { throw new IllegalStateException("Invalid MCP gRPC response", failure); }
    }

    @PreDestroy
    public void close() {
        channel.shutdown();
        try { channel.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!channel.isTerminated()) channel.shutdownNow();
    }

    private static final class BearerCredentials extends CallCredentials {
        private final InternalCredentialProperties credentials;

        private BearerCredentials(InternalCredentialProperties credentials) {
            this.credentials = credentials;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor,
                                         MetadataApplier applier) {
            appExecutor.execute(() -> {
                try {
                    String secret = credentials == null ? "" : credentials.resolvedSecret();
                    if (credentials != null && credentials.isEnabled() && secret.isBlank()) {
                        throw new IllegalStateException("MCP gRPC internal credential is required");
                    }
                    Metadata metadata = new Metadata();
                    metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + secret);
                    applier.apply(metadata);
                } catch (RuntimeException failure) {
                    applier.fail(Status.UNAUTHENTICATED.withCause(failure));
                }
            });
        }
    }
}
