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

    @PostMapping("/database-query/validate")
    public ApiResponse<AgentRuntimeTemplateDslImportService.ValidationResult> validateDatabaseQuery(
        @RequestBody AgentRuntimeTemplateDslImportService.ImportRequest request) {
        return ApiResponse.success(importService.validate(asDatabaseQuery(request)),
            "Database query runtime template DSL validated");
    }

    @PostMapping("/database-query/import")
    public ApiResponse<AgentRuntimeTemplateDslImportService.ImportResult> importDatabaseQuery(
        @RequestBody AgentRuntimeTemplateDslImportService.ImportRequest request) {
        return ApiResponse.success(importService.importTemplate(asDatabaseQuery(request)),
            "Database query runtime template DSL imported");
    }

    private AgentRuntimeTemplateDslImportService.ImportRequest asDatabaseQuery(
        AgentRuntimeTemplateDslImportService.ImportRequest request) {
        return new AgentRuntimeTemplateDslImportService.ImportRequest(
            request.dsl(), "DATABASE_QUERY", request.targetRegistry(), request.datasourceId());
    }
}
