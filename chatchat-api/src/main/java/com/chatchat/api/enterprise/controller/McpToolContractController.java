package com.chatchat.api.enterprise.controller;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolWorkflowContractSnapshot;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.enterprise.entity.McpToolWorkflowContract;
import com.chatchat.enterprise.service.DatabaseToolWorkflowContractCatalog;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Administrative lifecycle API for database-authoritative MCP tool contracts. */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/enterprise/mcp-tools/{toolId}/contracts")
@Tag(name = "MCP Tool Contracts", description = "Versioned MCP workflow-contract governance")
public class McpToolContractController {

    private final DatabaseToolWorkflowContractCatalog catalog;
    private final McpToolRegistryBridge registryBridge;

    @GetMapping
    @Operation(summary = "List all contract versions for an MCP tool")
    public ApiResponse<List<McpToolWorkflowContract>> list(@PathVariable String toolId) {
        return ApiResponse.success(catalog.listContracts(toolId));
    }

    @PutMapping("/{version}")
    @Operation(summary = "Revise a DRAFT MCP tool contract")
    public ApiResponse<McpToolWorkflowContract> revise(@PathVariable String toolId,
                                                       @PathVariable long version,
                                                       @RequestBody RevisionRequest request) {
        RevisionRequest value = request == null ? new RevisionRequest(null, null, null, null) : request;
        return ApiResponse.success(catalog.reviseDraft(toolId, version,
            new DatabaseToolWorkflowContractCatalog.DraftRevision(
                value.workflowRole(), value.protocolFamily(), value.inputEnvelope(), value.extensions())),
            "tool contract draft revised");
    }

    @PostMapping("/{version}/publish")
    @Operation(summary = "Atomically publish or roll back to an MCP tool contract version")
    public ApiResponse<ToolWorkflowContractSnapshot> publish(HttpServletRequest servletRequest,
                                                             @PathVariable String toolId,
                                                             @PathVariable long version,
                                                             @RequestParam long expectedActiveVersion) {
        Object authenticatedUsername = servletRequest.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME);
        String actor = authenticatedUsername == null ? null : String.valueOf(authenticatedUsername);
        if (actor == null || actor.isBlank()) actor = servletRequest.getRemoteUser();
        if (actor == null || actor.isBlank()) {
            throw new IllegalStateException("Authenticated publisher identity is required");
        }
        ToolWorkflowContractSnapshot published = catalog.publish(
            toolId, version, actor, expectedActiveVersion);
        // Publication transaction has completed because catalog is a separate proxied bean.
        // A failed discovery preserves only tools whose exact checksum is still ACTIVE.
        // If this publication changed the contract, the stale runtime entry is removed.
        registryBridge.refreshRegistry();
        return ApiResponse.success(published, "tool contract published");
    }

    public record RevisionRequest(ToolWorkflowRole workflowRole,
                                  String protocolFamily,
                                  String inputEnvelope,
                                  Map<String, Object> extensions) {
    }
}
