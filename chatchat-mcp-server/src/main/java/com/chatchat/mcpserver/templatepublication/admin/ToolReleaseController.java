package com.chatchat.mcpserver.templatepublication.admin;
import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Internal control-plane commands; protected by ControlPlaneManagementFilter. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-publications")
public class ToolReleaseController {
    private final ToolReleaseService service;
    @PutMapping("/{id}") public ApiResponse<ToolRelease> update(@RequestParam("tenantId") String tenantId,
        @RequestParam("actorId") String actorId,@PathVariable("id") String id,@RequestBody ToolReleaseService.DraftUpdate update) {
        return ApiResponse.success(service.updateDraft(tenantId,actorId,id,update));
    }
    @GetMapping public ApiResponse<List<ToolRelease>> list(@RequestParam("tenantId") String tenantId) { return ApiResponse.success(service.list(tenantId)); }
    @PostMapping public ApiResponse<ToolRelease> draft(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@RequestBody ToolReleaseService.Draft draft) {
        return ApiResponse.success(service.draft(tenantId,actorId,draft));
    }
    @PostMapping("/{id}/validate") public ApiResponse<ToolRelease> validate(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@PathVariable("id") String id) {
        return ApiResponse.success(service.validate(tenantId,actorId,id));
    }
    @PostMapping("/{id}/test") public ApiResponse<ToolRelease> test(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@PathVariable("id") String id,@RequestBody ToolReleaseService.ContractTest test) {
        return ApiResponse.success(service.test(tenantId,actorId,id,test));
    }
    @PostMapping("/{id}/publish") public ApiResponse<ToolRelease> publish(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@PathVariable("id") String id) {
        return ApiResponse.success(service.publish(tenantId,actorId,id,false));
    }
    @PostMapping("/{id}/rollback") public ApiResponse<ToolRelease> rollback(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@PathVariable("id") String id) {
        return ApiResponse.success(service.publish(tenantId,actorId,id,true));
    }
    @PostMapping("/{id}/offline") public ApiResponse<ToolRelease> offline(@RequestParam("tenantId") String tenantId,@RequestParam("actorId") String actorId,@PathVariable("id") String id) {
        return ApiResponse.success(service.offline(tenantId,actorId,id));
    }
}
