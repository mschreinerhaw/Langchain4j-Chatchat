package com.chatchat.common.kernel;

import java.util.Set;

/** Single source of truth for the internal Runtime OS Kernel ABI. */
public final class KernelProtocolCatalog {

    public static final String KERNEL_ABI_VERSION = "runtime_os_kernel.v1";
    public static final KernelProtocol RUNTIME_EXECUTION = new KernelProtocol(
        "chatchat.runtime.execution", "1.0", KernelChannel.IN_PROCESS, "application/json");
    public static final KernelProtocol MCP_BRIDGE = new KernelProtocol(
        "chatchat.mcp.bridge", "1.0", KernelChannel.MCP, "application/json");

    public static final KernelDataBoundary RUNTIME_BOUNDARY = new KernelDataBoundary(
        Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS,
            KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE,
            KernelDataDomain.OBSERVATIONS, KernelDataDomain.EVENTS, KernelDataDomain.ARTIFACTS),
        Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_RESULTS,
            KernelDataDomain.EVIDENCE, KernelDataDomain.OBSERVATIONS,
            KernelDataDomain.EVENTS, KernelDataDomain.ARTIFACTS),
        true,
        false
    );
    public static final KernelDataBoundary MCP_BOUNDARY = new KernelDataBoundary(
        Set.of(KernelDataDomain.CONTROL, KernelDataDomain.TOOL_ARGUMENTS),
        Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE),
        true,
        false
    );

    private KernelProtocolCatalog() {
    }
}
