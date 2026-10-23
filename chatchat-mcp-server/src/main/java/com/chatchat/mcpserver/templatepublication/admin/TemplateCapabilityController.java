package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Service-only API. The public API derives tenant, actor and management rights from its login. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/template-capabilities")
public class TemplateCapabilityController {
    private final TemplateCapabilityService service;
    public record Publish(String key, String revision) { }
    @GetMapping("/templates") public ApiResponse<List<Map<String,Object>>> templates(@RequestParam("tenantId") String tenant,
        @RequestParam("actorId") String actor, @RequestParam(value="management", defaultValue="false") boolean management) {
        return ApiResponse.success(service.templates(tenant, actor, management));
    }
    @GetMapping("/publications") public ApiResponse<List<Map<String,Object>>> publications(@RequestParam("tenantId") String tenant,
        @RequestParam("actorId") String actor) { return ApiResponse.success(service.publications(tenant, actor)); }
    @PostMapping("/publications") public ApiResponse<Map<String,Object>> publish(@RequestParam("tenantId") String tenant,
        @RequestParam("actorId") String actor, @RequestBody Publish input) {
        return ApiResponse.success(service.publish(tenant, actor, input.key(), input.revision()));
    }
    @PostMapping("/publications/{id}/offline") public ApiResponse<Map<String,Object>> offline(@RequestParam("tenantId") String tenant,
        @RequestParam("actorId") String actor, @PathVariable("id") String id) { return ApiResponse.success(service.offline(tenant, actor, id)); }
}
