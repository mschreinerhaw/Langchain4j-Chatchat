package com.chatchat.common.kernel;

/**
 * Root ABI of the ChatChat Runtime OS.
 *
 * <p>Every Runtime or MCP execution boundary exposes its identity, protocol and permitted data
 * range through this interface. The default invocation path performs protocol and scope checks,
 * then converts all failures into the canonical result envelope.</p>
 */
public interface RuntimeOsKernel<I, O> {

    KernelComponentDescriptor kernelDescriptor();

    KernelProtocol kernelProtocol();

    KernelDataBoundary kernelDataBoundary();

    O executeKernel(I payload, KernelDataScope scope);

    default KernelResult<O> invoke(KernelInvocation<I> invocation) {
        if (invocation == null) {
            throw new IllegalArgumentException("Kernel invocation is required");
        }
        try {
            validateInvocation(invocation);
            return KernelResult.success(invocation, executeKernel(invocation.payload(), invocation.scope()));
        } catch (KernelViolationException violation) {
            return KernelResult.failure(invocation, KernelStatus.REJECTED, violation.code(), violation.getMessage());
        } catch (RuntimeException failure) {
            return KernelResult.failure(invocation, KernelStatus.FAILURE, "KERNEL_EXECUTION_FAILED",
                failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    default void validateInvocation(KernelInvocation<I> invocation) {
        if (!KernelProtocolCatalog.KERNEL_ABI_VERSION.equals(invocation.abiVersion())) {
            throw new KernelViolationException("KERNEL_ABI_INCOMPATIBLE",
                "Unsupported Kernel ABI: " + invocation.abiVersion());
        }
        if (!kernelProtocol().isCompatibleWith(invocation.protocol())) {
            throw new KernelViolationException("KERNEL_PROTOCOL_INCOMPATIBLE",
                "Invocation protocol is incompatible with component " + kernelDescriptor().componentId());
        }
        kernelDataBoundary().validate(
            invocation.scope(), invocation.requestedReadData(), invocation.requestedWriteData());
    }
}
