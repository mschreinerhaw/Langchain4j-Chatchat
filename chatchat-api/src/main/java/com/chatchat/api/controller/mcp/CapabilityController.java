package com.chatchat.api.controller.mcp;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.api.service.CapabilityAccessService;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.mcp.capability.CapabilityManifest;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilityController {
    private final CapabilityAccessService capabilities;
    public CapabilityController(CapabilityAccessService capabilities) { this.capabilities = capabilities; }
    public record Search(String query, String agentId, Integer offset, Integer limit) { }
    @GetMapping
    public ApiResponse<CapabilityAccessService.Page> list(
        @RequestParam(name = "query", required = false) String query,
        @RequestParam(name = "agentId", required = false) String agentId,
        @RequestParam(name = "offset", defaultValue = "0") int offset,
        @RequestParam(name = "limit", defaultValue = "20") int limit, HttpServletRequest request) {
        return ApiResponse.success(capabilities.discover(scope(request, agentId), query, offset, limit));
    }
    @PostMapping("/search")
    public ApiResponse<CapabilityAccessService.Page> search(@RequestBody Search search, HttpServletRequest request) {
        return ApiResponse.success(capabilities.discover(scope(request, search.agentId()), search.query(),
            search.offset() == null ? 0 : search.offset(), search.limit() == null ? 20 : search.limit()));
    }
    @GetMapping("/{id}")
    public ApiResponse<CapabilityManifest> detail(@PathVariable("id") String id,
        @RequestParam(name = "agentId", required = false) String agentId, HttpServletRequest request) {
        return ApiResponse.success(capabilities.detail(scope(request, agentId), id));
    }
    @PostMapping("/{id}/invoke")
    public ApiResponse<ToolRuntimeExecution> invoke(@PathVariable("id") String id,
        @RequestParam(name = "agentId", required = false) String agentId,
        @RequestBody CapabilityAccessService.Invocation invocation, HttpServletRequest request) {
        return ApiResponse.success(capabilities.invoke(scope(request, agentId), id, invocation));
    }
    private CapabilityAccessService.Scope scope(HttpServletRequest request, String agentId) {
        Object tenant = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        Object user = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        return new CapabilityAccessService.Scope(tenant == null ? null : String.valueOf(tenant),
            user == null ? null : String.valueOf(user), agentId);
    }
}
