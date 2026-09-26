package com.chatchat.api.controller.datascience;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.federation.McpSkillFederationService;
import com.chatchat.common.constants.AppConstants;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data-science/domain-skills/mcp-sources")
public class McpSkillFederationController {
    private final McpSkillFederationService service;

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.sources(scope.tenantId());
        });
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody SourceRequest body, HttpServletRequest request) {
        return save(null, body, request);
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable("id") String id, @RequestBody SourceRequest body,
                                 HttpServletRequest request) {
        return save(id, body, request);
    }

    @PostMapping("/{id}/sync")
    public ApiResponse<?> synchronize(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            return service.synchronize(scope.tenantId(), id);
        });
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable("id") String id, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            service.delete(scope.tenantId(), id);
            return true;
        });
    }

    private ApiResponse<?> save(String id, SourceRequest body, HttpServletRequest request) {
        return call(() -> {
            Scope scope = scope(request);
            requireAdmin(scope);
            if (body == null) throw new IllegalArgumentException("MCP Skill source is required");
            return service.save(scope.tenantId(), scope.ownerId(),
                new McpSkillFederationService.SourceCommand(id, body.name(), body.endpoint(), body.authorization(),
                    body.defaultCategory(), body.allowPrivateNetwork(), body.enabled()));
        });
    }

    private Scope scope(HttpServletRequest request) {
        return new Scope(attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID, "default"),
            first(attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME, ""),
                attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID, "default")));
    }

    private String attribute(HttpServletRequest request, String key, String fallback) {
        Object value = request.getAttribute(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private String first(String first, String second) { return first == null || first.isBlank() ? second : first; }
    private void requireAdmin(Scope scope) {
        if (!"admin".equalsIgnoreCase(scope.ownerId())) throw new SecurityException("Only admin can manage MCP Skill sources");
    }

    private ApiResponse<?> call(Action action) {
        try {
            return ApiResponse.success(action.run());
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ApiResponse.error(409, ex.getMessage());
        } catch (SecurityException ex) {
            return ApiResponse.error(403, ex.getMessage());
        }
    }

    private interface Action { Object run(); }

    private record Scope(String tenantId, String ownerId) { }
    private record SourceRequest(String name, String endpoint, String authorization, String defaultCategory,
                                 boolean allowPrivateNetwork, boolean enabled) { }
}
