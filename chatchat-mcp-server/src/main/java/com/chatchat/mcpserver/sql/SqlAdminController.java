package com.chatchat.mcpserver.sql;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/sql")
public class SqlAdminController {

    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlTemplateService templateService;
    private final SqlMcpToolPublisher publisher;

    @GetMapping("/datasources")
    public ApiResponse<List<SqlDatasourceConfig>> listDatasources() {
        return ApiResponse.success(datasourceConfigService.listAll());
    }

    @PostMapping("/datasources")
    public ApiResponse<SqlDatasourceConfig> createDatasource(@RequestBody SqlDatasourceConfig request) {
        return ApiResponse.success(datasourceConfigService.create(request), "SQL datasource created");
    }

    @PutMapping("/datasources/{id}")
    public ApiResponse<SqlDatasourceConfig> updateDatasource(@PathVariable("id") String id,
                                                             @RequestBody SqlDatasourceConfig request) {
        return ApiResponse.success(datasourceConfigService.update(id, request), "SQL datasource updated");
    }

    @GetMapping("/templates")
    public ApiResponse<List<SqlTemplateConfig>> listTemplates() {
        return ApiResponse.success(templateService.listAll());
    }

    @PostMapping("/templates")
    public ApiResponse<SqlTemplateConfig> createTemplate(@RequestBody SqlTemplateConfig request) {
        return ApiResponse.success(templateService.save(request), "SQL template created");
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<SqlTemplateConfig> updateTemplate(@PathVariable("id") String id,
                                                         @RequestBody SqlTemplateConfig request) {
        return ApiResponse.success(templateService.update(id, request), "SQL template updated");
    }

    @PostMapping("/refresh-tools")
    public ApiResponse<Map<String, Object>> refreshTools() {
        publisher.refresh();
        return ApiResponse.success(Map.of("refreshed", true), "SQL MCP tools refreshed");
    }
}
