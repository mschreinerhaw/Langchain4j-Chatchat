package com.chatchat.common.bridge.api;

import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.bridge.RuntimeBridge;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;

import java.util.Set;

/** Strongly typed service port used by MCP adapters to communicate with governed API services. */
public interface McpApiBridge extends RuntimeBridge<McpApiCall, McpApiResult> {

    default BridgeResponse<McpApiResult> communicate(McpApiCall call, KernelDataScope scope) {
        if (call == null) throw new IllegalArgumentException("MCP/API call is required");
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        BridgeRequest<McpApiCall> request = new BridgeRequest<>(bridgeContract().version(),
            scope.requestId(), call.operation().operationCode(), scope,
            Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE, KernelDataDomain.EVENTS),
            call, call.extensions(), System.currentTimeMillis());
        return exchange(request);
    }
}
