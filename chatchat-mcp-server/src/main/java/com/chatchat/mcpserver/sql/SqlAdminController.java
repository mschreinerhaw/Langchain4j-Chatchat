package com.chatchat.mcpserver.sql;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SqlQueryExecuteService queryExecuteService;

    @GetMapping("/datasources")
    public ApiResponse<List<SqlDatasourceConfig>> listDatasources() {
        return ApiResponse.success(datasourceConfigService.listAll());
    }

    @PostMapping("/datasources")
    public ApiResponse<SqlDatasourceConfig> createDatasource(@RequestBody SqlDatasourceConfig request) {
        SqlDatasourceConfig saved = datasourceConfigService.create(request);
        publisher.refresh();
        return ApiResponse.success(saved, "SQL datasource created");
    }

    @PutMapping("/datasources/{id}")
    public ApiResponse<SqlDatasourceConfig> updateDatasource(@PathVariable("id") String id,
                                                             @RequestBody SqlDatasourceConfig request) {
        SqlDatasourceConfig saved = datasourceConfigService.update(id, request);
        publisher.refresh();
        return ApiResponse.success(saved, "SQL datasource updated");
    }

    @DeleteMapping("/datasources/{id}")
    public ApiResponse<Void> deleteDatasource(@PathVariable("id") String id) {
        datasourceConfigService.delete(id);
        publisher.refresh();
        return ApiResponse.success(null, "SQL datasource deleted");
    }

    @PostMapping("/datasources/test")
    public ApiResponse<SqlQueryResult> testDatasource(@RequestBody SqlDatasourceConfig request) {
        return ApiResponse.success(queryExecuteService.testConnection(request), "SQL datasource tested");
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

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable("id") String id) {
        templateService.delete(id);
        return ApiResponse.success(null, "SQL template deleted");
    }

    @PostMapping("/refresh-tools")
    public ApiResponse<Map<String, Object>> refreshTools() {
        publisher.refresh();
        return ApiResponse.success(Map.of("refreshed", true), "SQL MCP tools refreshed");
    }
}
