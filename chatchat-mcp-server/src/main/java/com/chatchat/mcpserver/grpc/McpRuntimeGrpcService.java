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
import com.chatchat.mcpserver.license.McpLicenseService;
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
    private final McpLicenseService licenseService;

    public McpRuntimeGrpcService(McpRuntimeKernel kernel, ObjectMapper objectMapper, int chunkBytes) {
        this(kernel, objectMapper, chunkBytes, null);
    }

    public McpRuntimeGrpcService(McpRuntimeKernel kernel, ObjectMapper objectMapper, int chunkBytes,
                                 McpLicenseService licenseService) {
        this.kernel = kernel;
        this.objectMapper = objectMapper;
        this.chunkBytes = chunkBytes;
        this.licenseService = licenseService;
    }

    @Override public void services(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respondLicensed(request, response, () -> kernel.services().stream()
            .filter(service -> kernel.tools(new McpToolQuery(service.serviceId(), null, java.util.Set.of()))
                .stream().anyMatch(this::allowedTool))
            .toList());
    }

    @Override public void tools(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respondLicensed(request, response, () -> kernel.tools(read(request, McpToolQuery.class)).stream()
            .filter(this::allowedTool)
            .toList());
    }

    @Override public void invoke(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> {
            McpServiceCall call = read(request, McpServiceCall.class);
            requireTool(call.toolName());
            return kernel.execute(call);
        });
    }

    @Override public void repair(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> {
            McpResultRepairRequest repair = read(request, McpResultRepairRequest.class);
            requireTool(repair.toolName());
            return kernel.repair(repair);
        });
    }

    @Override public void refresh(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respondLicensed(request, response, () -> { kernel.refresh(); return Map.of("refreshed", true); });
    }

    @Override public void contracts(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respondLicensed(request, response, kernel::contracts);
    }

    @Override public void audit(JsonRequest request, StreamObserver<PayloadChunk> response) {
        respond(request, response, () -> {
            McpContractAuditRequest audit = read(request, McpContractAuditRequest.class);
            if (audit.toolName() == null) requireRuntimeLicense();
            else requireTool(audit.toolName());
            return kernel.audit(audit);
        });
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

    private void respondLicensed(JsonRequest request, StreamObserver<PayloadChunk> observer,
                                 Supplier<?> operation) {
        respond(request, observer, () -> {
            requireRuntimeLicense();
            return operation.get();
        });
    }

    private void requireRuntimeLicense() {
        if (licenseService == null) return;
        String reason = licenseService.runtimeDenialReason();
        if (reason != null) throw Status.PERMISSION_DENIED.withDescription(reason).asRuntimeException();
    }

    private void requireTool(String toolName) {
        if (licenseService == null) return;
        String reason = licenseService.toolDenialReason(toolName);
        if (reason != null) throw Status.PERMISSION_DENIED.withDescription(reason).asRuntimeException();
    }

    private boolean allowedTool(com.chatchat.common.mcp.service.McpToolDescriptor tool) {
        return licenseService == null || licenseService.allowsTool(tool.localToolName())
            || licenseService.allowsTool(tool.remoteToolName());
    }

    private <T> T read(JsonRequest request, Class<T> type) {
        try { return objectMapper.readValue(request.getPayloadJson().toByteArray(), type); }
        catch (Exception failure) {
            throw Status.INVALID_ARGUMENT.withDescription("invalid MCP gRPC JSON payload")
                .withCause(failure).asRuntimeException();
        }
    }
}
