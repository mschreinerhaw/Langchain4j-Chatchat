package com.chatchat.api.datascience.sql;

import com.chatchat.api.service.ApiKernelScopeResolver;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Uses the existing MCP directory, authenticated envelope and Runtime kernel. No direct database connection. */
@Component @RequiredArgsConstructor
public class SqlStudioMetadataGateway {
    public static final String CONTRACT = "sql-studio.metadata.v2";
    private static final String TOOL = "sql_schema_context_query";
    private final McpRuntimeKernel kernel;
    private final ApiKernelScopeResolver scopes;

    public record Result(String serviceId, Map<String, Object> metadata) { }

    public Result invoke(HttpServletRequest request, String serviceId, Map<String, Object> arguments) {
        var authenticated = scopes.resolve(Map.of(), UUID.randomUUID().toString(), request);
        if (authenticated.userId() == null || authenticated.tenantId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated metadata caller is required");
        List<McpToolDescriptor> tools = kernel.tools(new McpToolQuery(serviceId, null, Set.of(TOOL))).stream()
            .filter(tool -> TOOL.equals(tool.remoteToolName())).toList();
        if (tools.size() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Select one registered MCP service providing SQL metadata discovery");
        var tool = tools.get(0);
        // Version support must be declared by the registered contract, not guessed from an endpoint name.
        Object properties = tool.inputSchema().get("properties");
        Object version = properties instanceof Map<?, ?> props ? props.get("contractVersion") : null;
        Object versions = version instanceof Map<?, ?> field ? field.get("enum") : null;
        if (!(versions instanceof Collection<?> supported) || !supported.contains(CONTRACT))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MCP SQL metadata v2 is not available on the selected service");
        var call = new McpServiceCall(null, null, tool.serviceId(), tool.remoteToolName(), arguments,
            Map.of("invocationSource", "sql_studio_metadata"), System.currentTimeMillis() + 30_000);
        var result = kernel.execute(scopes.bind(call, request));
        if (result == null || !result.successful()) {
            HttpStatus status = result != null && result.status() == McpServiceResultStatus.REJECTED ? HttpStatus.FORBIDDEN : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "SQL metadata operation failed or was denied");
        }
        Map<String, Object> data = payload(result.data(), 0);
        if (!CONTRACT.equals(data.get("contractVersion")) || !Boolean.TRUE.equals(data.get("doesNotExecuteSql")))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid metadata-only MCP response");
        return new Result(tool.serviceId(), data);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object value, int depth) {
        if (!(value instanceof Map<?, ?> map) || depth > 4) return Map.of();
        if (CONTRACT.equals(map.get("contractVersion"))) return (Map<String, Object>) map;
        for (String key : List.of("structuredContent", "structured_content", "data", "result")) {
            Map<String, Object> nested = payload(map.get(key), depth + 1);
            if (!nested.isEmpty()) return nested;
        }
        return Map.of();
    }
}
