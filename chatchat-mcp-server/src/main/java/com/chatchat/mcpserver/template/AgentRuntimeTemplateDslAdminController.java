package com.chatchat.mcpserver.template;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/template-dsl")
public class AgentRuntimeTemplateDslAdminController {

    private final AgentRuntimeTemplateDslImportService importService;

    @PostMapping("/validate")
    public ApiResponse<AgentRuntimeTemplateDslImportService.ValidationResult> validate(
        @RequestBody AgentRuntimeTemplateDslImportService.ImportRequest request) {
        return ApiResponse.success(importService.validate(request), "Agent runtime template DSL validated");
    }

    @PostMapping("/import")
    public ApiResponse<AgentRuntimeTemplateDslImportService.ImportResult> importTemplate(
        @RequestBody AgentRuntimeTemplateDslImportService.ImportRequest request) {
        return ApiResponse.success(importService.importTemplate(request), "Agent runtime template DSL imported");
    }
}
