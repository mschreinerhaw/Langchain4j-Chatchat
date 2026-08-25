package com.chatchat.common.kernel;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeOsKernelTest {

    private final RuntimeOsKernel<String, String> kernel = new RuntimeOsKernel<>() {
        @Override public KernelComponentDescriptor kernelDescriptor() {
            return new KernelComponentDescriptor("test-runtime", "runtime", "1", Set.of("execute"));
        }
        @Override public KernelProtocol kernelProtocol() { return KernelProtocolCatalog.RUNTIME_EXECUTION; }
        @Override public KernelDataBoundary kernelDataBoundary() { return KernelProtocolCatalog.RUNTIME_BOUNDARY; }
        @Override public String executeKernel(String payload, KernelDataScope scope) {
            return scope.partitionKey() + ":" + payload;
        }
    };

    @Test
    void invokesThroughCanonicalAbiEnvelope() {
        KernelInvocation<String> invocation = KernelInvocation.of("run",
            KernelProtocolCatalog.RUNTIME_EXECUTION, scope(), Set.of(KernelDataDomain.CONTROL), "payload");

        KernelResult<String> result = kernel.invoke(invocation);

        assertThat(result.successful()).isTrue();
        assertThat(result.abiVersion()).isEqualTo(KernelProtocolCatalog.KERNEL_ABI_VERSION);
        assertThat(result.invocationId()).isEqualTo(invocation.invocationId());
        assertThat(result.data()).isEqualTo("tenant-1:run-1:payload");
    }

    @Test
    void rejectsIncompatibleProtocolBeforeImplementationRuns() {
        KernelProtocol wrong = new KernelProtocol("other.protocol", "1.0",
            KernelChannel.IN_PROCESS, "application/json");
        KernelResult<String> result = kernel.invoke(KernelInvocation.of(
            "run", wrong, scope(), Set.of(KernelDataDomain.CONTROL), "payload"));

        assertThat(result.status()).isEqualTo(KernelStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("KERNEL_PROTOCOL_INCOMPATIBLE");
    }

    @Test
    void rejectsDataOutsideDeclaredBoundary() {
        KernelResult<String> result = kernel.invoke(KernelInvocation.of(
            "run", KernelProtocolCatalog.RUNTIME_EXECUTION, scope(),
            Set.of(KernelDataDomain.SECRETS), "payload"));

        assertThat(result.status()).isEqualTo(KernelStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("KERNEL_DATA_SCOPE_DENIED");
    }

    @Test
    void rejectsWriteDataOutsideDeclaredBoundary() {
        KernelResult<String> result = kernel.invoke(KernelInvocation.of(
            "run", KernelProtocolCatalog.RUNTIME_EXECUTION, scope(),
            Set.of(KernelDataDomain.CONTROL), Set.of(KernelDataDomain.TOOL_ARGUMENTS), "payload"));

        assertThat(result.status()).isEqualTo(KernelStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("KERNEL_DATA_SCOPE_DENIED");
    }

    @Test
    void rejectsCrossTenantScopeWhenComponentDoesNotAllowIt() {
        KernelDataScope crossTenant = new KernelDataScope("tenant-1", "user-1", "request-1",
            null, "run-1", "DEV", java.util.Map.of("sourceTenantId", "tenant-2"));
        KernelResult<String> result = kernel.invoke(KernelInvocation.of(
            "run", KernelProtocolCatalog.RUNTIME_EXECUTION, crossTenant,
            Set.of(KernelDataDomain.CONTROL), "payload"));

        assertThat(result.status()).isEqualTo(KernelStatus.REJECTED);
        assertThat(result.errorCode()).isEqualTo("KERNEL_CROSS_TENANT_DENIED");
    }

    @Test
    void secretDomainCannotBePublishedByAComponent() {
        assertThatThrownBy(() -> new KernelDataBoundary(
            Set.of(KernelDataDomain.SECRETS), Set.of(), true, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("secret material");
    }

    private KernelDataScope scope() {
        return new KernelDataScope("tenant-1", "user-1", "request-1", "conversation-1",
            "run-1", "DEV", null);
    }
}
