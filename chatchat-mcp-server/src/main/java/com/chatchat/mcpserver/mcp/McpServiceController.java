package com.chatchat.mcpserver.mcp;

import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/mcp-services")
public class McpServiceController {

    private final McpServiceRegistryService registryService;

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @GetMapping
    public ApiResponse<List<McpServiceView>> list() {
        return ApiResponse.success(registryService.listAll().stream().map(this::toView).toList());
    }

    /**
     * Creates the create.
     *
     * @param request the request value
     * @return the created create
     */
    @PostMapping
    public ApiResponse<McpServiceView> create(@RequestBody McpServiceUpsertRequest request) {
        return ApiResponse.success(toView(registryService.create(fromRequest(request))), "MCP service registered");
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param request the request value
     * @return the updated update
     */
    @PutMapping("/{id}")
    public ApiResponse<McpServiceView> update(@PathVariable("id") String id,
                                              @RequestBody McpServiceUpsertRequest request) {
        return ApiResponse.success(toView(registryService.update(id, fromRequest(request))), "MCP service updated");
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        registryService.delete(id);
        return ApiResponse.success(null, "MCP service deleted");
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<McpServiceView> setEnabled(@PathVariable("id") String id,
                                                  @RequestParam("enabled") boolean enabled) {
        return ApiResponse.success(toView(registryService.setEnabled(id, enabled)), "MCP service status updated");
    }

    /**
     * Performs the regenerate token operation.
     *
     * @param id the id value
     * @return the operation result
     */
    @PostMapping("/{id}/token")
    public ApiResponse<McpServiceView> regenerateToken(@PathVariable("id") String id) {
        return ApiResponse.success(toView(registryService.regenerateToken(id)), "MCP token regenerated");
    }

    /**
     * Performs the generate token operation.
     *
     * @return the operation result
     */
    @PostMapping("/generate-token")
    public ApiResponse<Map<String, String>> generateToken() {
        return ApiResponse.success(Map.of("token", registryService.generateUniqueToken()));
    }

    /**
     * Performs the heartbeat operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/heartbeat")
    public ApiResponse<McpServiceView> heartbeat(HttpServletRequest request) {
        return ApiResponse.success(toView(registryService.heartbeat(resolveMcpToken(request))), "heartbeat accepted");
    }

    /**
     * Creates the value from request.
     *
     * @param request the request value
     * @return the operation result
     */
    private McpServiceRegistration fromRequest(McpServiceUpsertRequest request) {
        McpServiceRegistration service = new McpServiceRegistration();
        service.setName(request.name());
        service.setEndpoint(request.endpoint());
        service.setServiceToken(request.serviceToken());
        service.setServiceType(request.serviceType());
        service.setPermissionGroup(request.permissionGroup());
        service.setEnabled(request.enabled() == null || request.enabled());
        service.setStatus(request.status());
        return service;
    }

    /**
     * Converts the value to view.
     *
     * @param service the service value
     * @return the converted view
     */
    private McpServiceView toView(McpServiceRegistration service) {
        return new McpServiceView(
            service.getId(),
            service.getName(),
            service.getEndpoint(),
            service.getServiceToken(),
            service.getServiceType(),
            service.getPermissionGroup(),
            service.isEnabled(),
            service.getStatus(),
            service.getLastHeartbeatAt() == null ? null : service.getLastHeartbeatAt().toEpochMilli(),
            service.getCreatedAt() == null ? null : service.getCreatedAt().toEpochMilli(),
            service.getUpdatedAt() == null ? null : service.getUpdatedAt().toEpochMilli()
        );
    }

    /**
     * Resolves the mcp token.
     *
     * @param request the request value
     * @return the resolved mcp token
     */
    private String resolveMcpToken(HttpServletRequest request) {
        String token = request.getHeader("X-MCP-TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("X-MCP-TOKEN is required");
        }
        return token.trim();
    }

    public record McpServiceUpsertRequest(
        String name,
        String endpoint,
        String serviceToken,
        String serviceType,
        String permissionGroup,
        Boolean enabled,
        String status
    ) {
    }

    public record McpServiceView(
        String id,
        String name,
        String endpoint,
        String serviceToken,
        String serviceType,
        String permissionGroup,
        boolean enabled,
        String status,
        Long lastHeartbeatAt,
        Long createdAt,
        Long updatedAt
    ) {
    }
}
