package com.chatchat.api.controller;

import com.chatchat.api.service.McpRuntimeAccessService;
import com.chatchat.api.service.ApiKernelScopeResolver;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.mcp.audit.McpContractAuditReport;
import com.chatchat.common.mcp.audit.McpContractAuditRequest;
import com.chatchat.common.mcp.audit.McpDomainContractDescriptor;
import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceDescriptor;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.mcp.service.McpToolDescriptor;
import com.chatchat.common.mcp.service.McpToolQuery;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.kernel.KernelHealth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Set;

/** Transport facade for the common MCP Runtime OS service protocol. */
@RestController
@RequestMapping(AppConstants.API_V1 + "/mcp/runtime")
@Tag(name = "MCP Runtime Bridge", description = "Dynamic MCP discovery, invocation and lossless result repair")
public class McpRuntimeBridgeController {
    private final McpRuntimeAccessService runtime;
    private final ApiKernelScopeResolver scopeResolver;

    public McpRuntimeBridgeController(McpRuntimeAccessService runtime, ApiKernelScopeResolver scopeResolver) {
        this.runtime = runtime;
        this.scopeResolver = scopeResolver;
    }

    @GetMapping("/services")
    @Operation(summary = "List dynamically injected MCP services")
    public ApiResponse<List<McpServiceDescriptor>> services() {
        return ApiResponse.success(runtime.services());
    }

    @GetMapping("/health")
    @Operation(summary = "Read MCP Runtime Kernel readiness, revision and discovery health")
    public ApiResponse<KernelHealth> health() {
        return ApiResponse.success(runtime.health());
    }

    @GetMapping("/tools")
    @Operation(summary = "Search the unified MCP tool contract directory")
    public ApiResponse<List<McpToolDescriptor>> tools(
        @RequestParam(required = false) String serviceId,
        @RequestParam(required = false) String capabilityCode,
        @RequestParam(required = false) Set<String> toolNames) {
        return ApiResponse.success(runtime.tools(new McpToolQuery(serviceId, capabilityCode, toolNames)));
    }

    @PostMapping("/invoke")
    @Operation(summary = "Invoke any MCP tool through the common protocol")
    public ApiResponse<McpServiceResult> invoke(@RequestBody McpServiceCall call, HttpServletRequest request) {
        return ApiResponse.success(runtime.invoke(scopeResolver.bind(call, request)));
    }

    @PostMapping("/repair")
    @Operation(summary = "Repair a failed model parse without discarding the raw MCP result")
    public ApiResponse<McpResultRepairResult> repair(@RequestBody McpResultRepairRequest repair,
                                                     HttpServletRequest request) {
        return ApiResponse.success(runtime.repair(scopeResolver.bind(repair, request)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh every dynamically injected MCP provider")
    public ApiResponse<Boolean> refresh() {
        runtime.refresh();
        return ApiResponse.success(true);
    }

    @GetMapping("/contracts")
    @Operation(summary = "List dynamically injected MCP service-domain contracts")
    public ApiResponse<List<McpDomainContractDescriptor>> contracts() {
        return ApiResponse.success(runtime.contracts());
    }

    @PostMapping("/contracts/audit")
    @Operation(summary = "Audit discovered MCP contracts and execution evidence with recovery guidance")
    public ApiResponse<McpContractAuditReport> audit(@RequestBody McpContractAuditRequest request) {
        return ApiResponse.success(runtime.audit(request));
    }
}
