package com.chatchat.mcpserver.templatepublication.admin;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/tool-publications/templates")
public class PublishedToolTemplateController {
    private final PublishedToolTemplateService service;
    @GetMapping public ApiResponse<List<PublishedToolTemplateService.Template>> templates(
        @RequestParam("tenantId") String tenant, @RequestParam("actorId") String actor,
        @RequestParam("domainCode") String domain) {
        return ApiResponse.success(service.templates(tenant, actor, domain));
    }
}
