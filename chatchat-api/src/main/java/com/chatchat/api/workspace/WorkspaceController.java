package com.chatchat.api.workspace;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService service;
    private final WorkspaceOriginService origins;
    @GetMapping("/workspaces/catalog") public ApiResponse<?> catalog() { return ApiResponse.success(service.licensedCatalog()); }
    @GetMapping("/workspaces/context/{type}/{id}") public ApiResponse<?> context(HttpServletRequest request, @PathVariable("type") String type, @PathVariable("id") String id) { return ApiResponse.success(origins.context(request, type, id).orElse(null)); }
    @GetMapping("/workspaces") public ApiResponse<?> available(HttpServletRequest request) { return ApiResponse.success(service.available(request)); }
    @GetMapping("/workspaces/{id}/manifest") public ApiResponse<?> manifest(HttpServletRequest request, @PathVariable("id") String id) { return ApiResponse.success(service.load(request, id)); }
    @GetMapping("/workspaces/preferences") public ApiResponse<?> preferences(HttpServletRequest request) { return ApiResponse.success(service.preference(request)); }
    @PutMapping("/workspaces/preferences") public ApiResponse<?> preferences(HttpServletRequest request, @RequestBody WorkspaceService.Preference update) { return ApiResponse.success(service.savePreference(request, update)); }
    @GetMapping("/enterprise/workspaces") public ApiResponse<?> all(HttpServletRequest request) { return ApiResponse.success(service.all(request)); }
    @PutMapping("/enterprise/workspaces/{id}") public ApiResponse<?> save(HttpServletRequest request, @PathVariable("id") String id, @RequestBody WorkspaceService.Update update) { return ApiResponse.success(service.save(request, id, update)); }
    @PutMapping("/enterprise/workspaces/default") public ApiResponse<?> setDefault(HttpServletRequest request, @RequestBody DefaultWorkspace update) { service.setDefault(request, update.workspaceId()); return ApiResponse.success(null); }
    public record DefaultWorkspace(String workspaceId) {}
}
