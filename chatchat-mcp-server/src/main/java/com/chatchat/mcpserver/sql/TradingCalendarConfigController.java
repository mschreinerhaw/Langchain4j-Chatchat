package com.chatchat.mcpserver.sql;

import com.chatchat.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/dynamic-date-params/trading-calendar")
@RequiredArgsConstructor
public class TradingCalendarConfigController {

    private static final Pattern BASIC_DATE = Pattern.compile("\\d{8}");
    private static final Pattern FUNCTION_TOKEN = Pattern.compile(
        "today|natural_date|month|month_start|month_end|trade_date(?:[+-]\\d+)?");

    private final TradingCalendarConfigService service;
    private final SqlQueryExecuteService queryExecuteService;
    private final DynamicDateParamService dynamicDateParamService;

    @GetMapping("/config")
    public ApiResponse<TradingCalendarConfigView> config() {
        return ApiResponse.success(toView(service.current()));
    }

    @PutMapping("/config")
    public ApiResponse<TradingCalendarConfigView> save(@RequestBody TradingCalendarConfigRequest request) {
        TradingCalendarConfig config = new TradingCalendarConfig();
        config.setEnabled(true);
        config.setDatasourceId(request.datasourceId());
        config.setSqlTemplate(request.sqlTemplate());
        return ApiResponse.success(toView(service.save(config)), "Trading calendar config saved");
    }

    @PostMapping("/test")
    public ApiResponse<TradingCalendarTestResult> test(@RequestBody TradingCalendarConfigRequest request) {
        TradingCalendarConfig config = new TradingCalendarConfig();
        config.setEnabled(true);
        config.setDatasourceId(request.datasourceId());
        config.setSqlTemplate(request.sqlTemplate());
        config = service.normalizeForExecution(config);
        SqlQueryResult queryResult = queryExecuteService.execute(Map.of(
            "datasourceId", config.getDatasourceId(),
            "sql", config.getSqlTemplate(),
            "maxRows", 10,
            "timeoutSeconds", 20,
            "purpose", "trading_calendar_config_test",
            "sourceTaskId", "admin-trading-calendar-config"
        ));
        return ApiResponse.success(validateTestResult(queryResult), "Trading calendar query tested");
    }

    @PostMapping("/function-test")
    public ApiResponse<TradingCalendarFunctionTestResult> functionTest(@RequestBody TradingCalendarFunctionTestRequest request) {
        String functionName = request.functionName() == null ? "" : request.functionName().trim();
        if (!FUNCTION_TOKEN.matcher(functionName).matches()) {
            throw new IllegalArgumentException("不支持的交易日动态参数函数：" + functionName);
        }
        TradingCalendarConfig config = new TradingCalendarConfig();
        config.setEnabled(true);
        config.setDatasourceId(request.datasourceId());
        config.setSqlTemplate(request.sqlTemplate());
        config = service.normalizeForExecution(config);
        SqlDatasourceConfig datasource = service.datasource(config);
        String value = dynamicDateParamService.resolveTokenForSource(datasource, config.getSqlTemplate(), functionName);
        return ApiResponse.success(new TradingCalendarFunctionTestResult(
            true,
            functionName,
            value,
            config.getDatasourceId(),
            config.getSqlTemplate(),
            "函数数据日获取成功"
        ), "Trading calendar function tested");
    }

    private TradingCalendarConfigView toView(TradingCalendarConfig config) {
        return new TradingCalendarConfigView(
            config.isEnabled(),
            config.getDatasourceId(),
            config.getSqlTemplate(),
            TradingCalendarConfig.DEFAULT_SQL,
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    public record TradingCalendarConfigRequest(
        Boolean enabled,
        String datasourceId,
        String sqlTemplate
    ) {
    }

    public record TradingCalendarFunctionTestRequest(
        String datasourceId,
        String sqlTemplate,
        String functionName
    ) {
    }

    public record TradingCalendarConfigView(
        boolean enabled,
        String datasourceId,
        String sqlTemplate,
        String defaultSqlTemplate,
        Long updatedAt
    ) {
    }

    public record TradingCalendarTestResult(
        boolean success,
        boolean querySuccess,
        boolean contractValid,
        String message,
        String errorMessage,
        List<String> columns,
        List<Map<String, Object>> sampleRows,
        SqlQueryResult queryResult
    ) {
    }

    public record TradingCalendarFunctionTestResult(
        boolean success,
        String functionName,
        String value,
        String datasourceId,
        String sqlTemplate,
        String message
    ) {
    }

    private TradingCalendarTestResult validateTestResult(SqlQueryResult queryResult) {
        if (!queryResult.success()) {
            return new TradingCalendarTestResult(
                false,
                false,
                false,
                "查询执行失败",
                queryResult.errorMessage(),
                queryResult.columns(),
                queryResult.rows(),
                queryResult
            );
        }
        List<String> errors = new ArrayList<>();
        if (queryResult.columns().size() < 2) {
            errors.add("SQL 至少需要返回两列：自然日、交易日");
        }
        if (queryResult.rows().isEmpty()) {
            errors.add("SQL 未返回样例行，无法作为交易日表使用");
        }
        if (queryResult.columns().size() >= 2) {
            String naturalColumn = queryResult.columns().get(0);
            String tradingColumn = queryResult.columns().get(1);
            for (int index = 0; index < queryResult.rows().size(); index++) {
                Map<String, Object> row = queryResult.rows().get(index);
                if (!isBasicDate(row.get(naturalColumn))) {
                    errors.add("第 " + (index + 1) + " 行自然日不是 yyyyMMdd 格式");
                    break;
                }
                if (!isBasicDate(row.get(tradingColumn))) {
                    errors.add("第 " + (index + 1) + " 行交易日不是 yyyyMMdd 格式");
                    break;
                }
            }
        }
        boolean valid = errors.isEmpty();
        return new TradingCalendarTestResult(
            valid,
            true,
            valid,
            valid ? "查询有效，返回结果符合交易日模板规则" : "查询可执行，但不符合交易日模板规则",
            valid ? null : String.join("；", errors),
            queryResult.columns(),
            new ArrayList<>(queryResult.rows()),
            queryResult
        );
    }

    private boolean isBasicDate(Object value) {
        if (value == null) {
            return false;
        }
        String text = value instanceof Number ? String.valueOf(((Number) value).longValue()) : String.valueOf(value).trim();
        return BASIC_DATE.matcher(text).matches();
    }
}
