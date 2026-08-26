package com.chatchat.common.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Stable control-plane health envelope for Runtime OS components. */
public record KernelHealth(
    String abiVersion,
    KernelComponentDescriptor component,
    KernelOperationalState state,
    long revision,
    long lastSuccessfulRefreshAt,
    String lastFailure,
    Map<String, Object> details,
    long observedAt
) {
    public KernelHealth {
        abiVersion = KernelProtocolCatalog.KERNEL_ABI_VERSION;
        if (component == null) throw new IllegalArgumentException("Kernel component is required");
        state = state == null ? KernelOperationalState.FAILED : state;
        revision = Math.max(0, revision);
        lastSuccessfulRefreshAt = Math.max(0, lastSuccessfulRefreshAt);
        details = details == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(details));
        observedAt = observedAt <= 0 ? System.currentTimeMillis() : observedAt;
    }

    public boolean ready() { return state == KernelOperationalState.READY; }
}
