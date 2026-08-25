package com.chatchat.common.kernel;

import java.util.Set;

/** Read/write data-range policy declared by one Kernel component. */
public record KernelDataBoundary(
    Set<KernelDataDomain> readable,
    Set<KernelDataDomain> writable,
    boolean tenantRequired,
    boolean crossTenantAllowed
) {
    public KernelDataBoundary {
        readable = readable == null ? Set.of() : Set.copyOf(readable);
        writable = writable == null ? Set.of() : Set.copyOf(writable);
        if (readable.contains(KernelDataDomain.SECRETS) || writable.contains(KernelDataDomain.SECRETS)) {
            throw new IllegalArgumentException("Kernel data boundaries cannot expose secret material");
        }
    }

    public void validate(KernelDataScope scope,
                         Set<KernelDataDomain> requestedReadData,
                         Set<KernelDataDomain> requestedWriteData) {
        if (scope == null) {
            throw new KernelViolationException("KERNEL_SCOPE_REQUIRED", "Kernel data scope is required");
        }
        if (tenantRequired && scope.tenantId() == null) {
            throw new KernelViolationException("KERNEL_TENANT_REQUIRED", "Kernel tenant scope is required");
        }
        if (!crossTenantAllowed) {
            Object sourceTenant = scope.attributes().get("sourceTenantId");
            if (sourceTenant != null && !String.valueOf(sourceTenant).equals(scope.tenantId())) {
                throw new KernelViolationException("KERNEL_CROSS_TENANT_DENIED",
                    "Kernel component does not allow cross-tenant data access");
            }
        }
        Set<KernelDataDomain> requestedReads = requestedReadData == null ? Set.of() : requestedReadData;
        if (!readable.containsAll(requestedReads)) {
            throw new KernelViolationException("KERNEL_DATA_SCOPE_DENIED",
                "Invocation requests read data outside the component boundary: " + requestedReads);
        }
        Set<KernelDataDomain> requestedWrites = requestedWriteData == null ? Set.of() : requestedWriteData;
        if (!writable.containsAll(requestedWrites)) {
            throw new KernelViolationException("KERNEL_DATA_SCOPE_DENIED",
                "Invocation requests write data outside the component boundary: " + requestedWrites);
        }
    }
}
