package com.chatchat.common.knowledge.template;

import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.bridge.RuntimeBridge;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;

import java.util.Set;

/** Domain port for template discovery and execution, implemented by any transport adapter. */
public interface TemplateServicePort extends RuntimeBridge<TemplateServiceCall, TemplateServiceResult> {

    default BridgeResponse<TemplateServiceResult> invoke(TemplateServiceCall call, KernelDataScope scope) {
        if (call == null) throw new IllegalArgumentException("template service call is required");
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        BridgeRequest<TemplateServiceCall> request = new BridgeRequest<>(bridgeContract().version(),
            scope.requestId(), call.operation().operationCode(), scope,
            Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE, KernelDataDomain.EVENTS),
            call, call.extensions(), System.currentTimeMillis());
        return exchange(request);
    }
}
