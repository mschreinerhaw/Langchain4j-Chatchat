package com.chatchat.common.bridge;

import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeBridgeTest {
    private final BridgeContract contract = new BridgeContract("test-api", "test_bridge.v1",
        KernelProtocolCatalog.API_BRIDGE, Set.of("query"), KernelProtocolCatalog.API_BOUNDARY);
    private final RuntimeBridge<Map<String, Object>, Map<String, Object>> bridge =
        new AbstractRuntimeBridge<>() {
            @Override public BridgeContract bridgeContract() { return contract; }
            @Override protected Map<String, Object> exchangePayload(BridgeRequest<Map<String, Object>> request) {
                return request.payload();
            }
        };

    @Test
    void preservesCompletePayloadInCanonicalResponse() {
        Map<String, Object> payload = Map.of("stdout", "container-1\ncontainer-2", "exitCode", 0);
        BridgeResponse<Map<String, Object>> response = bridge.exchange(request("test_bridge.v1", "query", payload,
            Set.of(KernelDataDomain.TOOL_ARGUMENTS), Set.of(KernelDataDomain.TOOL_RESULTS)));

        assertThat(response.successful()).isTrue();
        assertThat(response.data()).isEqualTo(payload);
        assertThat(response.requestId()).isEqualTo("request-1");
    }

    @Test
    void rejectsVersionOperationAndDataRangeDrift() {
        assertThat(bridge.exchange(request("test_bridge.v2", "query", Map.of(), Set.of(), Set.of())).status())
            .isEqualTo(BridgeStatus.REJECTED);
        assertThat(bridge.exchange(request("test_bridge.v1", "execute", Map.of(), Set.of(), Set.of())).status())
            .isEqualTo(BridgeStatus.REJECTED);
        BridgeResponse<Map<String, Object>> secret = bridge.exchange(request("test_bridge.v1", "query", Map.of(),
            Set.of(KernelDataDomain.SECRETS), Set.of()));
        assertThat(secret.status()).isEqualTo(BridgeStatus.REJECTED);
        assertThat(secret.errorCode()).isEqualTo("KERNEL_DATA_SCOPE_DENIED");
        BridgeRequest<Map<String, Object>> mismatchedTrace = new BridgeRequest<>("test_bridge.v1", "outer",
            "query", KernelDataScope.system("scope"), Set.of(), Set.of(), Map.of(), Map.of(),
            System.currentTimeMillis());
        assertThat(bridge.exchange(mismatchedTrace).errorCode()).isEqualTo("BRIDGE_SCOPE_MISMATCH");
    }

    private BridgeRequest<Map<String, Object>> request(String version, String operation,
                                                        Map<String, Object> payload,
                                                        Set<KernelDataDomain> reads,
                                                        Set<KernelDataDomain> writes) {
        return new BridgeRequest<>(version, "request-1", operation, KernelDataScope.system("request-1"),
            reads, writes, payload, Map.of(), System.currentTimeMillis());
    }
}
