package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.bridge.RuntimeBridge;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.kernel.RuntimeOsKernel;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpKernelBridgeTest {

    @Test
    void mcpExecutorIsAKernelComponentAndReceivesScopedInvocation() {
        AtomicReference<String> tenant = new AtomicReference<>();
        McpToolExecutor executor = input -> ToolOutput.success(input.getParameter("query"));
        ToolInput input = ToolInput.builder()
            .requestId("request-1")
            .userId("user-1")
            .parameters(Map.of("query", "docker ps"))
            .context(Map.of("tenantId", "tenant-1", "runId", "run-1", "env", "DEV"))
            .build();
        KernelInvocation<ToolInput> invocation = KernelInvocation.of("mcp.tool/linux",
            KernelProtocolCatalog.MCP_BRIDGE, McpKernelBridge.scope(input),
            Set.of(KernelDataDomain.TOOL_ARGUMENTS), input);

        KernelResult<ToolOutput> kernelResult = executor.invoke(invocation);
        tenant.set(kernelResult.scope().tenantId());
        ToolOutput bridged = McpKernelBridge.invoke("linux", executor, input);

        assertThat(RuntimeOsKernel.class).isAssignableFrom(McpToolExecutor.class);
        assertThat(RuntimeBridge.class).isAssignableFrom(McpKernelBridge.class);
        assertThat(kernelResult.successful()).isTrue();
        assertThat(tenant.get()).isEqualTo("tenant-1");
        assertThat(bridged.isSuccess()).isTrue();
        assertThat(bridged.getData()).isEqualTo("docker ps");
    }

    @Test
    void kernelFailureBecomesStructuredMcpFailure() {
        McpToolExecutor executor = input -> { throw new IllegalStateException("backend unavailable"); };

        ToolOutput output = McpKernelBridge.invoke("failing-tool", executor,
            ToolInput.builder().requestId("request-2").context(Map.of("tenantId", "tenant-1")).build());

        assertThat(output.isSuccess()).isFalse();
        assertThat(output.getErrorMessage()).contains("KERNEL_EXECUTION_FAILED").contains("backend unavailable");
    }
}
