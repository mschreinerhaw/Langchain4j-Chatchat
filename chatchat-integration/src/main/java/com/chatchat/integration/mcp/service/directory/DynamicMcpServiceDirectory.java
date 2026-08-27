package com.chatchat.integration.mcp.service.directory;

import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpResultRepairer;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceDirectory;
import com.chatchat.common.mcp.service.McpServiceProvider;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime implementation that resolves MCP providers and repairers from the live Spring container. */
@Service
public class DynamicMcpServiceDirectory implements McpServiceDirectory {
    private final ObjectProvider<McpServiceProvider> providerBeans;
    private final ObjectProvider<McpResultRepairer> repairerBeans;

    public DynamicMcpServiceDirectory(ObjectProvider<McpServiceProvider> providerBeans,
                                      ObjectProvider<McpResultRepairer> repairerBeans) {
        this.providerBeans = providerBeans;
        this.repairerBeans = repairerBeans;
    }

    @Override
    public List<McpServiceDescriptor> services() {
        Map<String, McpServiceDescriptor> result = new LinkedHashMap<>();
        providers().forEach(provider -> provider.services().forEach(service -> {
            McpServiceDescriptor previous = result.putIfAbsent(service.serviceId(), service);
            if (previous != null) throw new IllegalStateException("Duplicate MCP service ownership: " + service.serviceId());
        }));
        return result.values().stream().sorted(Comparator.comparing(McpServiceDescriptor::serviceId)).toList();
    }

    @Override
    public List<McpToolDescriptor> tools(McpToolQuery query) {
        McpToolQuery effective = query == null ? McpToolQuery.all() : query;
        Map<String, McpToolDescriptor> result = new LinkedHashMap<>();
        providers().forEach(provider -> provider.tools(effective).stream().filter(effective::matches).forEach(tool -> {
            String key = tool.serviceId() + "/" + tool.localToolName();
            if (result.putIfAbsent(key, tool) != null) throw new IllegalStateException("Duplicate MCP tool ownership: " + key);
        }));
        return result.values().stream()
            .sorted(Comparator.comparing(McpToolDescriptor::serviceId).thenComparing(McpToolDescriptor::localToolName))
            .toList();
    }

    @Override
    public McpServiceResult invoke(McpServiceCall call) {
        if (call.expired(System.currentTimeMillis())) {
            return failure(call, McpServiceResultStatus.REJECTED, "MCP_CALL_DEADLINE_EXCEEDED",
                "MCP call deadline has elapsed", false, "REPLAN");
        }
        List<McpServiceProvider> matches = providers().stream()
            .filter(provider -> provider.supports(call.serviceId(), call.toolName())).toList();
        if (matches.isEmpty()) return failure(call, McpServiceResultStatus.NOT_FOUND, "MCP_TOOL_NOT_FOUND",
            "No MCP provider owns the requested service/tool", true, "REFRESH_OR_DISCOVER");
        if (matches.size() > 1) return failure(call, McpServiceResultStatus.REJECTED, "MCP_PROVIDER_AMBIGUOUS",
            "More than one MCP provider owns the requested service/tool", false, "FIX_PROVIDER_REGISTRATION");
        try {
            return matches.get(0).invoke(call);
        } catch (RuntimeException error) {
            return failure(call, McpServiceResultStatus.FAILED, "MCP_PROVIDER_FAILURE",
                error.getMessage(), true, "RETRY_OR_REPAIR");
        }
    }

    @Override
    public McpResultRepairResult repair(McpResultRepairRequest request) {
        McpResultRepairRequest effective = request;
        if (request.expectedOutputSchema().isEmpty()) {
            Map<String, Object> schema = findTool(request.serviceId(), request.toolName())
                .map(McpToolDescriptor::outputSchema).orElse(Map.of());
            effective = request.withExpectedOutputSchema(schema);
        }
        List<String> failures = new ArrayList<>();
        for (McpResultRepairer repairer : repairers()) {
            if (!repairer.supports(effective)) continue;
            try {
                return repairer.repair(effective);
            } catch (RuntimeException error) {
                failures.add(repairer.repairerId() + ": " + error.getMessage());
            }
        }
        return new McpResultRepairResult(null, request.requestId(), request.serviceId(), request.toolName(),
            McpServiceResultStatus.FAILED, null, request.rawResult(), Map.of("repairerFailures", failures),
            "No MCP result repairer could normalize the response");
    }

    @Override
    public void refresh() {
        providers().forEach(McpServiceProvider::refresh);
    }

    private List<McpServiceProvider> providers() {
        return providerBeans.orderedStream().toList();
    }

    private List<McpResultRepairer> repairers() {
        return repairerBeans.stream().sorted(Comparator.comparingInt(McpResultRepairer::priority).reversed()).toList();
    }

    private McpServiceResult failure(McpServiceCall call, McpServiceResultStatus status, String code,
                                     String message, boolean retryable, String action) {
        return new McpServiceResult(null, call.requestId(), call.serviceId(), call.toolName(), status,
            null, null, code, message, retryable, action, Map.of(), 0);
    }
}
