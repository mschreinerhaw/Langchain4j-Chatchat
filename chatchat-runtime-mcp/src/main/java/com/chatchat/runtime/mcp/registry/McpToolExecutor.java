package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.kernel.KernelComponentDescriptor;
import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelProtocol;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.RuntimeOsKernel;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

import java.util.Set;

@FunctionalInterface
public interface McpToolExecutor extends RuntimeOsKernel<ToolInput, ToolOutput> {
    ToolOutput execute(ToolInput input);

    @Override
    default ToolOutput executeKernel(ToolInput payload, KernelDataScope scope) {
        return execute(payload);
    }

    @Override
    default KernelComponentDescriptor kernelDescriptor() {
        return new KernelComponentDescriptor(getClass().getName(), "mcp-tool-executor", "1",
            Set.of("tool-execute"));
    }

    @Override
    default KernelProtocol kernelProtocol() {
        return KernelProtocolCatalog.MCP_BRIDGE;
    }

    @Override
    default KernelDataBoundary kernelDataBoundary() {
        return KernelProtocolCatalog.MCP_BOUNDARY;
    }

    default McpToolRuntimeStatus runtimeStatus() {
        return McpToolRuntimeStatus.AVAILABLE;
    }
}
