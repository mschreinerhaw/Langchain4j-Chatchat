package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.bridge.AbstractRuntimeBridge;
import com.chatchat.common.bridge.BridgeContract;
import com.chatchat.common.bridge.BridgeException;
import com.chatchat.common.bridge.BridgeRequest;
import com.chatchat.common.bridge.BridgeResponse;
import com.chatchat.common.bridge.BridgeStatus;
import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.kernel.KernelStatus;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds and validates the canonical Kernel envelope for every MCP tool invocation. */
public final class McpKernelBridge extends AbstractRuntimeBridge<ToolInput, ToolOutput> {
    public static final String BRIDGE_VERSION = "runtime_mcp_bridge.v1";

    private final String toolName;
    private final McpToolExecutor executor;
    private final BridgeContract contract;

    private McpKernelBridge(String toolName, McpToolExecutor executor) {
        this.toolName = toolName;
        this.executor = executor;
        this.contract = new BridgeContract("mcp-tool/" + toolName, BRIDGE_VERSION,
            KernelProtocolCatalog.MCP_BRIDGE, Set.of("mcp.tool/" + toolName),
            KernelProtocolCatalog.MCP_BOUNDARY);
    }

    public static ToolOutput invoke(String toolName, McpToolExecutor executor, ToolInput input) {
        if (executor == null) return ToolOutput.failure("MCP tool executor is required: " + toolName);
        McpKernelBridge bridge = new McpKernelBridge(toolName, executor);
        BridgeRequest<ToolInput> request = BridgeRequest.of(
            bridge.bridgeContract(),
            "mcp.tool/" + toolName,
            scope(input),
            Set.of(KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE),
            input
        );
        BridgeResponse<ToolOutput> response = bridge.exchange(request);
        if (response.successful() && response.data() != null) return response.data();
        return ToolOutput.failure(response.errorCode() + ": " + response.errorMessage());
    }

    @Override
    public BridgeContract bridgeContract() {
        return contract;
    }

    @Override
    protected ToolOutput exchangePayload(BridgeRequest<ToolInput> request) {
        KernelInvocation<ToolInput> invocation = new KernelInvocation<>(
            KernelProtocolCatalog.KERNEL_ABI_VERSION, request.requestId(), request.operation(),
            contract.protocol(), request.scope(), request.requestedReadData(), request.requestedWriteData(),
            request.payload(), request.metadata(), request.createdAt());
        KernelResult<ToolOutput> result = executor.invoke(invocation);
        if (result.successful() && result.data() != null) return result.data();
        throw new BridgeException(result.status() == KernelStatus.REJECTED
            ? BridgeStatus.REJECTED : BridgeStatus.FAILURE, result.errorCode(), result.errorMessage());
    }

    static KernelDataScope scope(ToolInput input) {
        Map<String, Object> context = input == null || input.getContext() == null
            ? Map.of() : input.getContext();
        String requestId = firstText(input == null ? null : input.getRequestId(),
            text(context.get("requestId")), UUID.randomUUID().toString());
        return new KernelDataScope(
            firstText(text(context.get("tenantId")), "system"),
            firstText(input == null ? null : input.getUserId(), text(context.get("userId"))),
            requestId,
            firstText(input == null ? null : input.getConversationId(), text(context.get("conversationId"))),
            text(context.get("runId")),
            firstText(text(context.get("environment")), text(context.get("env"))),
            Map.of("source", "mcp-kernel-bridge")
        );
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
