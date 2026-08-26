package com.chatchat.mcpserver.grpc;

import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.runtime.McpRuntimeTransportPort;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.mcp.grpc.McpGrpcPayloads;
import com.chatchat.mcp.grpc.v1.JsonRequest;
import com.chatchat.mcp.grpc.v1.PayloadChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpRuntimeGrpcServiceTest {

    @Test
    void streamsLosslessLargeKernelResult() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpRuntimeKernel kernel = mock(McpRuntimeKernel.class);
        McpServiceCall call = new McpServiceCall(null, "request-1", "linux", "execute",
            Map.of("templateId", "CHECK_LOG"), Map.of("tenantId", "tenant-a"), 0);
        String stdout = "x".repeat(3 * 1024 * 1024);
        McpServiceResult result = new McpServiceResult(null, call.requestId(), call.serviceId(),
            call.toolName(), McpServiceResultStatus.SUCCESS, Map.of("stdout", stdout),
            Map.of("stdout", stdout), null, null, false, null, Map.of(), 0);
        when(kernel.execute(call)).thenReturn(result);
        McpRuntimeGrpcService service = new McpRuntimeGrpcService(kernel, mapper, 1024 * 1024);
        CapturingObserver observer = new CapturingObserver();
        JsonRequest request = JsonRequest.newBuilder()
            .setProtocolVersion(McpRuntimeTransportPort.PROTOCOL_VERSION)
            .setRequestId(call.requestId())
            .setPayloadJson(ByteString.copyFrom(mapper.writeValueAsBytes(call)))
            .build();

        service.invoke(request, observer);

        assertThat(observer.failure).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.chunks).hasSizeGreaterThan(5);
        McpServiceResult restored = mapper.readValue(
            McpGrpcPayloads.assemble(observer.chunks.iterator(), 10L * 1024 * 1024),
            McpServiceResult.class);
        assertThat(((Map<?, ?>) restored.data()).get("stdout")).isEqualTo(stdout);
        assertThat(((Map<?, ?>) restored.rawData()).get("stdout")).isEqualTo(stdout);
    }

    private static final class CapturingObserver implements StreamObserver<PayloadChunk> {
        private final List<PayloadChunk> chunks = new ArrayList<>();
        private Throwable failure;
        private boolean completed;
        @Override public void onNext(PayloadChunk value) { chunks.add(value); }
        @Override public void onError(Throwable error) { failure = error; }
        @Override public void onCompleted() { completed = true; }
    }
}
