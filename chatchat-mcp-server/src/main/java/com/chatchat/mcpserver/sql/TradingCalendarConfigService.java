package com.chatchat.mcpserver.sql;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TradingCalendarConfigService {

    private final TradingCalendarConfigRepository repository;
    private final SqlDatasourceConfigService datasourceConfigService;

    public TradingCalendarConfig current() {
        return normalize(repository.findById(TradingCalendarConfig.DEFAULT_ID)
            .orElseGet(TradingCalendarConfig::new), false);
    }

    @Transactional
    public TradingCalendarConfig save(TradingCalendarConfig request) {
        TradingCalendarConfig current = repository.findById(TradingCalendarConfig.DEFAULT_ID)
            .orElseGet(TradingCalendarConfig::new);
        current.setId(TradingCalendarConfig.DEFAULT_ID);
        current.setEnabled(true);
        current.setDatasourceId(request.getDatasourceId());
        current.setSqlTemplate(request.getSqlTemplate());
        return repository.save(normalize(current, true));
    }

    public SqlDatasourceConfig datasource(TradingCalendarConfig config) {
        if (config == null || config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            return null;
        }
        return datasourceConfigService.getEnabled(config.getDatasourceId());
    }

    public TradingCalendarConfig normalizeForExecution(TradingCalendarConfig request) {
        TradingCalendarConfig config = new TradingCalendarConfig();
        config.setId(TradingCalendarConfig.DEFAULT_ID);
        config.setEnabled(true);
        config.setDatasourceId(request == null ? null : request.getDatasourceId());
        config.setSqlTemplate(request == null ? null : request.getSqlTemplate());
        return normalize(config, true);
    }

    private TradingCalendarConfig normalize(TradingCalendarConfig config, boolean requireDatasource) {
        config.setId(TradingCalendarConfig.DEFAULT_ID);
        config.setDatasourceId(blankToNull(config.getDatasourceId()));
        config.setSqlTemplate(firstText(config.getSqlTemplate(), TradingCalendarConfig.DEFAULT_SQL));
        if (requireDatasource && config.getDatasourceId() == null) {
            throw new IllegalArgumentException("交易日数据源模板必须选择数据库资产");
        }
        validateSelect(config.getSqlTemplate());
        return config;
    }

    private void validateSelect(String sql) {
        String value = sql == null ? "" : sql.trim();
        if (!value.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new IllegalArgumentException("交易日查询 SQL 只允许 SELECT");
        }
        if (value.contains(";")) {
            throw new IllegalArgumentException("交易日查询 SQL 不允许多语句");
        }
    }

    private String firstText(String value, String fallback) {
        String normalized = blankToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
