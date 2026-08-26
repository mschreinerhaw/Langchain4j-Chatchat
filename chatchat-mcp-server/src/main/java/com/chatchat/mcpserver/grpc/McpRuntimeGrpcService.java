package com.chatchat.mcpserver.grpc;

import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.mcp.grpc.McpGrpcPayloads;
import com.chatchat.mcp.grpc.v1.JsonRequest;
import com.chatchat.mcp.grpc.v1.McpRuntimeServiceGrpc;
import com.chatchat.mcp.grpc.v1.PayloadChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.function.Supplier;

/** gRPC southbound facade over the authoritative MCP Runtime OS Kernel. */
public final class McpRuntimeGrpcService extends McpRuntimeServiceGrpc.McpRuntimeServiceImplBase {
    private final McpRuntimeKernel kernel;
    private final ObjectMapper objectMapper;
    private final int chunkBytes;

    public McpRuntimeGrpcService(McpRuntimeKernel kernel, ObjectMapper objectMapper, int chunkBytes) {
        this.kernel = kernel;
        this.objectMapper = objectMapper;
        this.chunkBytes = chunkBytes;
    }

    @Override public void services(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, kernel::services);
    }

    @Override public void tools(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> kernel.tools(read(request, McpToolQuery.class)));
    }

    @Override public void invoke(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> kernel.execute(read(request, McpServiceCall.class)));
    }

    @Override public void repair(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> kernel.repair(read(request, McpResultRepairRequest.class)));
    }

    @Override public void refresh(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> { kernel.refresh(); return Map.of("refreshed", true); });
    }

    @Override public void contracts(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, kernel::contracts);
    }

    @Override public void audit(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> kernel.audit(read(request, McpContractAuditRequest.class)));
    }

    @Override public void health(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, kernel::kernelHealth);
    }

    private void respond(JsonRequest request, StreamObserver<PayloadChunk> observer, Supplier<?> operation) {
        try {
            if (!McpRuntimeTransportPort.PROTOCOL_VERSION.equals(request.getProtocolVersion())) {
                throw Status.FAILED_PRECONDITION
                    .withDescription("unsupported MCP gRPC protocol " + request.getProtocolVersion())
                    .asRuntimeException();
            }
            if (observer instanceof ServerCallStreamObserver<PayloadChunk> serverObserver) {
                serverObserver.setCompression("gzip");
            }
            byte[] json = objectMapper.writeValueAsBytes(operation.get());
            McpGrpcPayloads.emit(request.getRequestId(), json, chunkBytes, observer::onNext);
            observer.onCompleted();
        } catch (io.grpc.StatusRuntimeException failure) {
            observer.onError(failure);
        } catch (Exception failure) {
            observer.onError(Status.INTERNAL.withDescription("MCP Runtime operation failed")
                .withCause(failure).asRuntimeException());
        }
    }

    private <T> T read(JsonRequest request, Class<T> type) {
        try { return objectMapper.readValue(request.getPayloadJson().toByteArray(), type); }
        catch (Exception failure) {
            throw Status.INVALID_ARGUMENT.withDescription("invalid MCP gRPC JSON payload")
                .withCause(failure).asRuntimeException();
        }
    }
}
