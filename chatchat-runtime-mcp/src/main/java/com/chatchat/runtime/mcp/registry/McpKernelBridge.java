package com.chatchat.runtime.mcp.registry;

import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelInvocation;
import com.chatchat.common.kernel.KernelProtocolCatalog;
import com.chatchat.common.kernel.KernelResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds and validates the canonical Kernel envelope for every MCP tool invocation. */
public final class McpKernelBridge {

    private McpKernelBridge() {
    }

    public static ToolOutput invoke(String toolName, McpToolExecutor executor, ToolInput input) {
        if (executor == null) return ToolOutput.failure("MCP tool executor is required: " + toolName);
        KernelInvocation<ToolInput> invocation = KernelInvocation.of(
            "mcp.tool/" + toolName,
            KernelProtocolCatalog.MCP_BRIDGE,
            scope(input),
            Set.of(KernelDataDomain.TOOL_ARGUMENTS),
            Set.of(KernelDataDomain.TOOL_RESULTS, KernelDataDomain.EVIDENCE),
            input
        );
        KernelResult<ToolOutput> result = executor.invoke(invocation);
        if (result.successful() && result.data() != null) return result.data();
        return ToolOutput.failure(result.errorCode() + ": " + result.errorMessage());
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
