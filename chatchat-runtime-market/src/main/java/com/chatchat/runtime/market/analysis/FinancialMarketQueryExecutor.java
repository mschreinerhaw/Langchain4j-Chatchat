package com.chatchat.runtime.market.analysis;

import com.chatchat.runtime.market.storage.FinancialDatasetDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.Reader;
import java.sql.Clob;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes read-only analysis against the application-owned financial tables without
 * exposing the application JDBC credentials or any MCP configuration tables.
 */
@Service
public class FinancialMarketQueryExecutor {

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
        "(?i)\\b(?:from|join)\\s+[`\"]?([a-zA-Z][a-zA-Z0-9_]*)[`\"]?");
    private static final Pattern COMMA_JOIN = Pattern.compile(
        "(?i)\\bfrom\\s+[`\"]?[a-zA-Z][a-zA-Z0-9_]*[`\"]?(?:\\s+[a-zA-Z][a-zA-Z0-9_]*)?\\s*,");
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?i)\\b(?:insert|update|delete|drop|alter|create|truncate|merge|replace|grant|revoke|"
            + "call|execute|outfile|dumpfile|load_file|sleep|benchmark|information_schema)\\b");
    private static final Set<String> CATALOG_TABLES = Set.of("market_asset_catalog", "data_schema_registry");

    private final DataSource dataSource;

    public FinancialMarketQueryExecutor(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public QueryResult execute(String sql, Map<String, Object> parameters, int requestedMaxRows, int timeoutSeconds) {
        String safeSql = validate(sql);
        int maxRows = Math.max(1, Math.min(requestedMaxRows, 500));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setMaxRows(maxRows);
        jdbc.setQueryTimeout(Math.max(1, Math.min(timeoutSeconds, 60)));
        List<Map<String, Object>> rows = new NamedParameterJdbcTemplate(jdbc)
            .queryForList(safeSql, parameters == null ? Map.of() : parameters)
            .stream()
            .map(this::normalizeRow)
            .toList();
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        return new QueryResult(safeSql, columns, rows, rows.size(), maxRows, rows.size() >= maxRows);
    }

    String validate(String sql) {
        String normalized = sql == null ? "" : sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isBlank() || !normalized.toLowerCase(Locale.ROOT).startsWith("select ")) {
            throw new IllegalArgumentException("内置金融数据源只允许单条 SELECT 查询");
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*")
            || normalized.contains("*/") || normalized.contains("#")) {
            throw new IllegalArgumentException("内置金融数据源不允许多语句或 SQL 注释");
        }
        if (FORBIDDEN.matcher(normalized).find()) {
            throw new IllegalArgumentException("内置金融数据查询包含禁止的关键字或函数");
        }
        if (COMMA_JOIN.matcher(normalized).find()) {
            throw new IllegalArgumentException("内置金融数据源不允许逗号连接表，请使用显式且受治理的 JOIN");
        }
        Set<String> allowed = new LinkedHashSet<>(CATALOG_TABLES);
        allowed.addAll(FinancialDatasetDefinition.governedTableNames());
        Matcher matcher = TABLE_REFERENCE.matcher(normalized);
        int references = 0;
        while (matcher.find()) {
            references++;
            String table = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!allowed.contains(table)) {
                throw new IllegalArgumentException("内置金融数据源禁止访问非金融治理表: " + table);
            }
        }
        if (references == 0) {
            throw new IllegalArgumentException("金融分析 SQL 必须引用已治理的金融数据表");
        }
        return normalized;
    }

    private Map<String, Object> normalizeRow(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(key, normalizeValue(value)));
        return Collections.unmodifiableMap(normalized);
    }

    private Object normalizeValue(Object value) {
        if (!(value instanceof Clob clob)) {
            return value;
        }
        try (Reader reader = clob.getCharacterStream()) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    public record QueryResult(
        String sql,
        List<String> columns,
        List<Map<String, Object>> rows,
        int rowCount,
        int maxRows,
        boolean possiblyTruncated
    ) {
    }
}
