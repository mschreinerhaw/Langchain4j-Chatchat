package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-categories")
public class ToolCategoryController {
    private final ToolCategoryService service;
    @GetMapping public ApiResponse<List<ToolCategoryService.Category>> list(@RequestParam("tenantId") String tenant) {return ApiResponse.success(service.list(tenant));}
    @PostMapping public ApiResponse<ToolCategoryService.Category> create(@RequestParam("tenantId") String tenant,@RequestBody ToolCategoryService.Edit body) {return ApiResponse.success(service.create(tenant,body));}
    @PutMapping("/{id}") public ApiResponse<ToolCategoryService.Category> rename(@RequestParam("tenantId") String tenant,@PathVariable("id") String id,@RequestBody ToolCategoryService.Edit body) {return ApiResponse.success(service.rename(tenant,id,body));}
    @DeleteMapping("/{id}") public ApiResponse<Boolean> delete(@RequestParam("tenantId") String tenant,@PathVariable("id") String id,@RequestBody ToolCategoryService.Removal body) {service.delete(tenant,id,body);return ApiResponse.success(true);}
    @PutMapping("/assignments/{logicalId}") public ApiResponse<Boolean> assign(@RequestParam("tenantId") String tenant,@PathVariable("logicalId") String logical,@RequestBody ToolCategoryService.Assignment body) {service.assign(tenant,logical,body);return ApiResponse.success(true);}
}
