package com.chatchat.integration.mcp.grpc;

import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.mcp.grpc.McpGrpcPayloads;
import com.chatchat.mcp.grpc.v1.JsonRequest;
import com.chatchat.mcp.grpc.v1.McpRuntimeServiceGrpc;
import com.chatchat.mcp.grpc.v1.PayloadChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcMcpRuntimeTransportClientTest {

    @Test
    void readsChunkedServiceDirectoryOverRealGrpcChannel() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<McpServiceDescriptor> expected = List.of(
            new McpServiceDescriptor("linux", "Linux", "runtime", "grpc", true,
                Map.of("large", "x".repeat(2 * 1024 * 1024))));
        Server server = NettyServerBuilder.forPort(0)
            .addService(new McpRuntimeServiceGrpc.McpRuntimeServiceImplBase() {
                @Override
                public void services(JsonRequest request, StreamObserver<PayloadChunk> observer) {
                    try {
                        McpGrpcPayloads.emit(request.getRequestId(), mapper.writeValueAsBytes(expected),
                            256 * 1024, observer::onNext);
                        observer.onCompleted();
                    } catch (Exception failure) {
                        observer.onError(failure);
                    }
                }
            }).build().start();
        McpGrpcClientProperties properties = new McpGrpcClientProperties();
        properties.setPort(server.getPort());
        properties.setMaxResponseBytes(8L * 1024 * 1024);
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        credentials.setEnabled(false);
        GrpcMcpRuntimeTransportClient client = new GrpcMcpRuntimeTransportClient(
            mapper, properties, credentials);
        try {
            assertThat(client.services()).isEqualTo(expected);
        } finally {
            client.close();
            server.shutdownNow();
        }
    }
}
