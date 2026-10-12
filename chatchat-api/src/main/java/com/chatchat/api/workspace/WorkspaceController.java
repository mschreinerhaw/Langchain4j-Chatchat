package com.chatchat.api.workspace;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "chatchat.workspace", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkspaceController {
    private final WorkspaceService service;
    private String user(HttpServletRequest request) {
        var id = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); return String.valueOf(id);
    }
    @GetMapping("/workspaces") public ApiResponse<?> available(HttpServletRequest request) { return ApiResponse.success(service.available(user(request))); }
    @GetMapping("/workspaces/{id}/manifest") public ApiResponse<?> manifest(@PathVariable String id, HttpServletRequest request) { return ApiResponse.success(service.accessible(user(request), id)); }
    @GetMapping("/workspaces/preferences") public ApiResponse<?> preference(HttpServletRequest request) { return ApiResponse.success(service.preference(user(request))); }
    public record PreferenceUpdate(String defaultWorkspaceId, String lastWorkspaceId, long expectedRevision) {}
    @PutMapping("/workspaces/preferences") public ApiResponse<?> preference(@RequestBody PreferenceUpdate update, HttpServletRequest request) {
        return ApiResponse.success(service.setPreference(user(request), update.defaultWorkspaceId(), update.lastWorkspaceId(), update.expectedRevision()));
    }
    @GetMapping("/enterprise/workspaces") public ApiResponse<?> all(HttpServletRequest request) { return ApiResponse.success(service.all(user(request))); }
    @PostMapping("/enterprise/workspaces") public ApiResponse<?> create(@RequestBody WorkspaceService.Update update, HttpServletRequest request) {
        if (update.manifest() == null) throw new IllegalArgumentException("Manifest required");
        return ApiResponse.success(service.save(user(request), update.manifest().workspaceId(), update, true));
    }
    @PutMapping("/enterprise/workspaces/{id}") public ApiResponse<?> update(@PathVariable String id, @RequestBody WorkspaceService.Update update, HttpServletRequest request) {
        return ApiResponse.success(service.save(user(request), id, update, false));
    }
    @ExceptionHandler(WorkspaceService.AccessDenied.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<?> denied() { return ApiResponse.error(403, "Workspace unavailable or unauthorized"); }
    @ExceptionHandler(WorkspaceService.Conflict.class) @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> conflict(WorkspaceService.Conflict error) { return ApiResponse.error(409, error.getMessage()); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> invalid(IllegalArgumentException error) { return ApiResponse.error(400, error.getMessage()); }
}
