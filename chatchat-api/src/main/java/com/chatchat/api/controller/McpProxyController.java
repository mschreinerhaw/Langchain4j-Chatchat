package com.chatchat.api.controller;

import com.chatchat.common.mcp.proxy.McpTransportProxyPort;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP -> stdio MCP proxy endpoint.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/mcp/proxy")
@Tag(name = "MCP Proxy", description = "HTTP to stdio MCP proxy endpoints")
public class McpProxyController {

    private final McpTransportProxyPort proxyPort;

    /**
     * Performs the forward operation.
     *
     * @param serviceId the service id value
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/services/{serviceId}/rpc")
    @Operation(summary = "Forward one JSON-RPC request to local stdio MCP server")
    public ApiResponse<Map<String, Object>> forward(@PathVariable("serviceId") String serviceId,
                                                    @RequestBody Map<String, Object> request) {
        try {
            return ApiResponse.success(proxyPort.forward(serviceId, request));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    /**
     * Closes the session.
     *
     * @param serviceId the service id value
     * @return the operation result
     */
    @PostMapping("/services/{serviceId}/close")
    @Operation(summary = "Close one stdio proxy session")
    public ApiResponse<Map<String, Object>> closeSession(@PathVariable("serviceId") String serviceId) {
        proxyPort.closeSession(serviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("closed", true);
        data.put("serviceId", serviceId);
        return ApiResponse.success(data, "proxy session closed");
    }
}
