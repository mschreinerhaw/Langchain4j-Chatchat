package com.chatchat.agents.runtime.coding;

import com.chatchat.common.kernel.KernelDataScope;

/** Mandatory, nonblocking trusted identity/grant check; task scope is not authentication. No allow-all default. */
@FunctionalInterface
public interface CodingTaskAccess {
    enum Action { SUBMIT, READ, STOP, RECOVER, WORKER, EVIDENCE }
    void require(KernelDataScope scope, Action action);

    /** Implementations with request-local identity may capture READ-only authority for a subscription. */
    default CodingTaskAccess subscriptionAccess(KernelDataScope scope) {
        require(scope, Action.READ);
        return this;
    }
}
