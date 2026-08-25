package com.chatchat.common.bridge.api;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpApiBridgeTest {

    @Test
    void communicatesThroughTypedPayloadsAndKernelDataRanges() {
        TestBridge bridge = new TestBridge();
        Map<String, Object> extensions = new java.util.LinkedHashMap<>();
        extensions.put("limit", 10);
        extensions.put("optionalCursor", null);
        McpApiCall call = McpApiCall.search("customer profile", Map.of("env", "DEV"),
            Map.of("tenant", "tenant-a"), extensions);

        BridgeResponse<McpApiResult> response = bridge.communicate(
            call, new KernelDataScope("tenant-a", "user-a", "request-a", null, "run-a", "DEV", Map.of()));

        assertThat(response.successful()).isTrue();
        assertThat(response.data().schemaVersion()).isEqualTo(McpApiResult.SCHEMA_VERSION);
        assertThat(response.data().operation()).isEqualTo(McpApiOperation.TEMPLATE_SEARCH);
        assertThat(response.data().toPayload())
            .containsEntry("communicationRequestId", "request-a")
            .containsEntry("communicationOperation", "api.service/query");
        assertThat(bridge.observed.requestedReadData())
            .containsExactlyInAnyOrder(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS);
        assertThat(bridge.observed.requestedWriteData())
            .containsExactlyInAnyOrder(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE,
                KernelDataDomain.EVENTS);
    }

    @Test
    void definesGovernedExecutionWithoutExposingRawHttpDefinitions() {
        McpApiCall call = McpApiCall.execute("customer_profile_v1", Map.of("customerId", "C-1"),
            Map.of("tenantId", "tenant-a"), "task-1", System.currentTimeMillis() + 10_000);

        assertThat(call.operation()).isEqualTo(McpApiOperation.TEMPLATE_EXECUTE);
        assertThat(call.templateId()).isEqualTo("customer_profile_v1");
        assertThat(call.parameters()).containsOnlyKeys("customerId");
        assertThat(McpApiCall.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("url", "method", "headers", "body");
        assertThatThrownBy(() -> new McpApiCall("mcp_api_call.v2", call.operation(), call.caller(),
            call.targetService(), null, call.templateId(), Map.of(), call.parameters(), Map.of(),
            Map.of(), null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpApiCall.search("query", Map.of(), Map.of(),
            Map.of("url", "https://unapproved.example")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Raw API transport definition is forbidden");
    }

    private static final class TestBridge extends AbstractRuntimeBridge<McpApiCall, McpApiResult>
        implements McpApiBridge {
        private final BridgeContract contract = new BridgeContract("test-mcp-api", "test_mcp_api.v1",
            KernelProtocolCatalog.API_BRIDGE, Set.of(McpApiOperation.TEMPLATE_SEARCH.operationCode()),
            KernelProtocolCatalog.API_BOUNDARY);
        private BridgeRequest<McpApiCall> observed;

        @Override public BridgeContract bridgeContract() { return contract; }

        @Override protected McpApiResult exchangePayload(BridgeRequest<McpApiCall> request) {
            observed = request;
            return new McpApiResult(McpApiResult.SCHEMA_VERSION, request.requestId(),
                request.payload().operation(), McpApiResultStatus.SUCCESS,
                Map.of("templates", List.of()), List.of(), false, request.metadata(),
                System.currentTimeMillis());
        }
    }
}
