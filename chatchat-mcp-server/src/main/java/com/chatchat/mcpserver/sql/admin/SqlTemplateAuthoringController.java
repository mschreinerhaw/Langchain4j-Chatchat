package com.chatchat.mcpserver.sql.admin;

import com.chatchat.mcpserver.sql.template.SqlTemplateAuthoringService;
import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Internal control-plane endpoint; public Studio derives identity and source versions server-side. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/template-capabilities/sql-authoring")
public class SqlTemplateAuthoringController {
    private final SqlTemplateAuthoringService service;
    @GetMapping("/datasources") public ApiResponse<?> sources(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor){return ApiResponse.success(service.sources(tenant,actor));}
    @GetMapping public ApiResponse<?> list(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@RequestParam("projectId")String project,@RequestParam("fileId")String file){return ApiResponse.success(service.list(tenant,actor,project,file));}
    @PostMapping public ApiResponse<?> create(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@RequestBody SqlTemplateAuthoringService.Write body){return ApiResponse.success(service.create(tenant,actor,body));}
    @PutMapping("/{id}") public ApiResponse<?> update(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@PathVariable("id")String id,@RequestBody SqlTemplateAuthoringService.Write body){return ApiResponse.success(service.update(tenant,actor,id,body));}
    @PostMapping("/{id}/test") public ApiResponse<?> test(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@PathVariable("id")String id,@RequestBody SqlTemplateAuthoringService.Action body){return ApiResponse.success(service.test(tenant,actor,id,body));}
    @PostMapping("/{id}/publish") public ApiResponse<?> publish(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@PathVariable("id")String id,@RequestBody SqlTemplateAuthoringService.Action body){return ApiResponse.success(service.publish(tenant,actor,id,body));}
    @PostMapping("/{id}/rollback") public ApiResponse<?> rollback(@RequestParam("tenantId")String tenant,@RequestParam("actorId")String actor,@PathVariable("id")String id,@RequestBody SqlTemplateAuthoringService.Action body){return ApiResponse.success(service.rollback(tenant,actor,id,body));}
}
