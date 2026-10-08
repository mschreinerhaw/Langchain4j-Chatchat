package com.chatchat.mcpserver.datacapability.admin;

import com.chatchat.common.response.ApiResponse;
import com.chatchat.mcpserver.datacapability.calendar.*;
import com.chatchat.mcpserver.datacapability.connection.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.*;
import com.chatchat.mcpserver.datacapability.importing.*;
import com.chatchat.mcpserver.datacapability.publication.CapabilityMcpPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/data-capabilities")
public class CapabilityAdminController {
    private final CapabilityService capabilities;
    private final CapabilityExecutionService executions;
    private final CapabilityImportService imports;
    private final CapabilityMcpPublisher publisher;
    private final QueryConnectionService connections;
    private final TradingCalendarService calendar;
    private final SqlDatasourceConfigService sqlConnections;

    @GetMapping public ApiResponse<List<CapabilityDefinition>> list(@RequestParam(value = "type", required = false) CapabilityType type) {
        return ApiResponse.success(capabilities.list(type));
    }
    @PostMapping @Transactional public ApiResponse<CapabilityDefinition> create(@RequestBody CapabilityDefinition d) {
        CapabilityDefinition result = capabilities.create(d); publisher.refreshPublication(); return ApiResponse.success(result);
    }
    @PutMapping("/{code}") @Transactional public ApiResponse<CapabilityDefinition> update(@PathVariable("code") String code, @RequestBody CapabilityDefinition d) {
        CapabilityDefinition result = capabilities.update(code, d); publisher.refreshPublication(); return ApiResponse.success(result);
    }
    @DeleteMapping("/{code}") @Transactional public ApiResponse<Void> delete(@PathVariable("code") String code) {
        capabilities.delete(code); publisher.refreshPublication(); return ApiResponse.success(null);
    }
    @PostMapping("/{code}/test") public ApiResponse<CapabilityExecution> test(@PathVariable("code") String code, @RequestBody(required = false) Map<String, Object> parameters) {
        return ApiResponse.success(executions.invoke(code, parameters, true, false));
    }
    @PostMapping("/test") public ApiResponse<CapabilityExecution> draft(@RequestBody DraftTest request) {
        return ApiResponse.success(executions.execute(request.definition(), request.parameters(), true, false));
    }
    @PostMapping("/{code}/invoke") public ApiResponse<CapabilityExecution> invoke(@PathVariable("code") String code,
        @RequestBody(required = false) Map<String, Object> parameters, @RequestParam(value = "async", defaultValue = "false") boolean async) {
        return ApiResponse.success(executions.invoke(code, parameters, false, async));
    }
    @GetMapping("/executions/{id}") public ApiResponse<CapabilityExecution> execution(@PathVariable("id") String id) {
        return ApiResponse.success(executions.get(id));
    }
    @GetMapping("/{code}/executions") public ApiResponse<List<CapabilityExecution>> history(@PathVariable("code") String code) {
        return ApiResponse.success(executions.history(code));
    }
    @PostMapping("/publication/refresh") public ApiResponse<?> publish() { return ApiResponse.success(publisher.refreshPublication()); }
    @GetMapping("/connections") public ApiResponse<List<QueryConnectionService.AssetReference>> connections(
        @RequestParam(value = "type", required = false) CapabilityType type) { return ApiResponse.success(connections.list(type)); }
    @GetMapping("/connections/sql") public ApiResponse<?> sqlConnections() {
        return ApiResponse.success(sqlConnections.listAll().stream().map(c -> Map.of(
            "id", c.getId(), "name", c.getName(), "databaseType", c.getDatabaseType(), "enabled", c.isEnabled(),
            "trino", c.getJdbcUrl().startsWith("jdbc:trino:"))).toList());
    }
    @GetMapping("/calendar/days") public ApiResponse<List<TradingDay>> calendar(@RequestParam("market") String market,
        @RequestParam("start") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate start, @RequestParam("end") @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate end) { return ApiResponse.success(calendar.range(market, start, end)); }
    @PostMapping("/calendar/days") public ApiResponse<List<TradingDay>> calendar(@RequestBody List<TradingCalendarService.DayRequest> days) {
        return ApiResponse.success(calendar.save(days));
    }
    @GetMapping("/imports/template") public ApiResponse<CapabilityDefinition> template(@RequestParam("type") CapabilityType type) { return ApiResponse.success(imports.template(type)); }
    @PostMapping("/imports") public ApiResponse<CapabilityImportBatch> importDefinitions(@RequestBody ImportRequest request) {
        CapabilityImportBatch result = imports.importDefinitions(request.definitions(), request.dryRun());
        if (!request.dryRun() && result.getSucceeded() > 0) {
            try { publisher.refreshPublication(); }
            catch (RuntimeException ex) { result = imports.publicationFailed(result, ex.getMessage()); }
        }
        return ApiResponse.success(result);
    }
    @GetMapping("/imports") public ApiResponse<List<CapabilityImportBatch>> imports() { return ApiResponse.success(imports.history()); }
    @GetMapping("/imports/{id}") public ApiResponse<CapabilityImportBatch> importBatch(@PathVariable("id") String id) { return ApiResponse.success(imports.get(id)); }
    public record DraftTest(CapabilityDefinition definition, Map<String, Object> parameters) {}
    public record ImportRequest(List<JsonNode> definitions, boolean dryRun) {}
}
