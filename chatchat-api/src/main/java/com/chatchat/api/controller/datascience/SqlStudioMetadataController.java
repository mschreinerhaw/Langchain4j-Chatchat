package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.sql.SqlStudioMetadataService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/data-science/sql/projects/{projectId}/metadata")
public class SqlStudioMetadataController {
    private final SqlStudioMetadataService metadata;
    private final WorkspaceService workspaces;
    @GetMapping("/providers")
    public ApiResponse<?> providers(@PathVariable("projectId") String projectId, HttpServletRequest request) {
        workspaces.requireAvailable(request, "data-science");
        return ApiResponse.success(metadata.providers(request, projectId));
    }
    @GetMapping("/assets")
    public ApiResponse<?> assets(@PathVariable("projectId") String projectId,
        @RequestParam(value="serviceId", required=false) String serviceId,
        @RequestParam(value="page", defaultValue="1") int page,
        @RequestParam(value="pageSize", defaultValue="25") int pageSize,
        @RequestParam(value="expectedSnapshotId", required=false) String snapshot, HttpServletRequest request) {
        workspaces.requireAvailable(request, "data-science");
        return ApiResponse.success(metadata.assets(request, projectId, serviceId, page, pageSize, snapshot));
    }
    @PostMapping("/contexts")
    public ApiResponse<?> create(@PathVariable("projectId") String projectId,
        @RequestBody SqlStudioMetadataService.Create body, HttpServletRequest request) {
        workspaces.requireAvailable(request, "data-science");
        return ApiResponse.success(metadata.create(request, projectId, body));
    }
    @GetMapping("/contexts/{discoveryId}")
    public ApiResponse<?> get(@PathVariable("projectId") String projectId, @PathVariable("discoveryId") String discoveryId, HttpServletRequest request) {
        workspaces.requireAvailable(request, "data-science");
        return ApiResponse.success(metadata.get(request, projectId, discoveryId));
    }
    @PostMapping("/contexts/{discoveryId}/operations")
    public ApiResponse<?> operate(@PathVariable("projectId") String projectId, @PathVariable("discoveryId") String discoveryId,
        @RequestBody SqlStudioMetadataService.Operation body, HttpServletRequest request) {
        workspaces.requireAvailable(request, "data-science");
        return ApiResponse.success(metadata.operate(request, projectId, discoveryId, body));
    }
}
