package com.chatchat.mcpserver.sql;

import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DynamicDateParamService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern DYNAMIC_TOKEN = Pattern.compile(
        "\\$\\{\\s*(today|natural_date|month|month_start|month_end|trade_date(?:[+-]\\d+)?)\\s*}");
    private static final Pattern NAMED_DYNAMIC_PARAM = Pattern.compile(
        "(?<!:):\\s*(today|natural_date|month|month_start|month_end|trade_date)\\b");
    private static final Pattern MUSTACHE_DYNAMIC_PARAM = Pattern.compile(
        "\\{\\{\\s*(today|natural_date|month|month_start|month_end|trade_date(?:[+-]\\d+)?)\\s*}}");
    private static final long CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L;

    private final DynamicJdbcDriverLoader driverLoader;
    private final TradingCalendarConfigService tradingCalendarConfigService;
    private final Clock clock;
    private final ConcurrentMap<String, TradingCalendarCache> calendarCache = new ConcurrentHashMap<>();

    @Autowired
    public DynamicDateParamService(DynamicJdbcDriverLoader driverLoader,
                                   TradingCalendarConfigService tradingCalendarConfigService) {
        this(driverLoader, tradingCalendarConfigService, Clock.systemDefaultZone());
    }

    public DynamicDateParamService(DynamicJdbcDriverLoader driverLoader) {
        this(driverLoader, null, Clock.systemDefaultZone());
    }

    DynamicDateParamService(DynamicJdbcDriverLoader driverLoader, Clock clock) {
        this(driverLoader, null, clock);
    }

    DynamicDateParamService(DynamicJdbcDriverLoader driverLoader,
                            TradingCalendarConfigService tradingCalendarConfigService,
                            Clock clock) {
        this.driverLoader = driverLoader;
        this.tradingCalendarConfigService = tradingCalendarConfigService;
        this.clock = clock;
    }

    public Map<String, Object> enrichParameters(Map<String, Object> parameters, SqlDatasourceConfig datasource,
                                                String sqlTemplate) {
        Map<String, Object> values = new LinkedHashMap<>(parameters == null ? Map.of() : parameters);
        LocalDate currentDate = LocalDate.now(clock);
        values.putIfAbsent("today", formatDate(currentDate));
        values.putIfAbsent("natural_date", formatDate(currentDate));
        values.putIfAbsent("month", currentDate.format(MONTH));
        values.putIfAbsent("month_start", formatDate(currentDate.withDayOfMonth(1)));
        values.putIfAbsent("month_end", formatDate(YearMonth.from(currentDate).atEndOfMonth()));

        for (String reference : dynamicReferences(sqlTemplate, values)) {
            if (reference.startsWith("trade_date")) {
                values.putIfAbsent(reference, tradeDate(datasource, currentDate, tradeOffset(reference)));
            }
        }
        if (shouldProvideCurrentTradeDate(sqlTemplate, values)) {
            values.putIfAbsent("trade_date", tradeDate(datasource, currentDate, 0));
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        values.forEach((key, value) -> resolved.put(key, resolveValue(value, datasource, currentDate)));
        return resolved;
    }

    public String resolveSqlPlaceholders(String sql, SqlDatasourceConfig datasource) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        return resolveText(sql, datasource, LocalDate.now(clock));
    }

    public String resolveTokenForSource(SqlDatasourceConfig datasource, String calendarSql, String token) {
        LocalDate currentDate = LocalDate.now(clock);
        return switch (token) {
            case "today", "natural_date" -> formatDate(currentDate);
            case "month" -> currentDate.format(MONTH);
            case "month_start" -> formatDate(currentDate.withDayOfMonth(1));
            case "month_end" -> formatDate(YearMonth.from(currentDate).atEndOfMonth());
            default -> {
                if (!token.startsWith("trade_date")) {
                    throw new IllegalArgumentException("Unsupported dynamic date parameter: " + token);
                }
                int today = Integer.parseInt(formatDate(currentDate));
                TradingCalendarSource source = new TradingCalendarSource(
                    datasource,
                    firstText(calendarSql, TradingCalendarConfig.DEFAULT_SQL)
                );
                yield String.valueOf(tradingCalendar(source).find(today, tradeOffset(token)));
            }
        };
    }

    /**
     * Checks one date against the centrally configured trading calendar.
     * A date outside the returned calendar range is treated as an invalid/empty
     * decision instead of being guessed as a trading day.
     */
    public TradingDayDecision checkTradingDay(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("交易日判断日期不能为空");
        }
        TradingCalendar calendar = tradingCalendar(tradingCalendarSource(null));
        int basicDate = Integer.parseInt(formatDate(date));
        int index = Arrays.binarySearch(calendar.naturalDays(), basicDate);
        if (index < 0) {
            throw new IllegalStateException("交易日查询结果不包含日期 " + formatDate(date));
        }
        int mappedTradingDay = calendar.tradingDays()[index];
        return new TradingDayDecision(date, basicDate == mappedTradingDay, mappedTradingDay);
    }

    private Object resolveValue(Object value, SqlDatasourceConfig datasource, LocalDate currentDate) {
        if (value instanceof String text) {
            return resolveText(text, datasource, currentDate);
        }
        return value;
    }

    private String resolveText(String text, SqlDatasourceConfig datasource, LocalDate currentDate) {
        Matcher matcher = DYNAMIC_TOKEN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolveToken(token, datasource, currentDate)));
        }
        matcher.appendTail(buffer);
        Matcher mustacheMatcher = MUSTACHE_DYNAMIC_PARAM.matcher(buffer.toString());
        StringBuffer resolved = new StringBuffer();
        while (mustacheMatcher.find()) {
            String token = mustacheMatcher.group(1);
            mustacheMatcher.appendReplacement(resolved,
                Matcher.quoteReplacement(resolveToken(token, datasource, currentDate)));
        }
        mustacheMatcher.appendTail(resolved);
        return resolved.toString();
    }

    private String resolveToken(String token, SqlDatasourceConfig datasource, LocalDate currentDate) {
        return switch (token) {
            case "today", "natural_date" -> formatDate(currentDate);
            case "month" -> currentDate.format(MONTH);
            case "month_start" -> formatDate(currentDate.withDayOfMonth(1));
            case "month_end" -> formatDate(YearMonth.from(currentDate).atEndOfMonth());
            default -> {
                if (!token.startsWith("trade_date")) {
                    throw new IllegalArgumentException("Unsupported dynamic date parameter: " + token);
                }
                yield tradeDate(datasource, currentDate, tradeOffset(token));
            }
        };
    }

    private boolean shouldProvideCurrentTradeDate(String sqlTemplate, Map<String, Object> values) {
        if (values.containsKey("trade_date")) {
            return false;
        }
        if (containsReference(sqlTemplate, "trade_date")) {
            return true;
        }
        return values.values().stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .anyMatch(value -> containsReference(value, "trade_date"));
    }

    private List<String> dynamicReferences(String sqlTemplate, Map<String, Object> values) {
        List<String> references = new ArrayList<>();
        collectTokenReferences(sqlTemplate, references);
        collectMustacheReferences(sqlTemplate, references);
        if (values != null) {
            values.values().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .forEach(value -> collectTokenReferences(value, references));
        }
        return references.stream().distinct().toList();
    }

    private void collectTokenReferences(String text, List<String> references) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = DYNAMIC_TOKEN.matcher(text);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
    }

    private void collectMustacheReferences(String text, List<String> references) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = MUSTACHE_DYNAMIC_PARAM.matcher(text);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
    }

    private boolean containsReference(String text, String reference) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return DYNAMIC_TOKEN.matcher(text).results().anyMatch(match -> reference.equals(match.group(1)))
            || MUSTACHE_DYNAMIC_PARAM.matcher(text).results().anyMatch(match -> reference.equals(match.group(1)))
            || NAMED_DYNAMIC_PARAM.matcher(text).results().anyMatch(match -> reference.equals(match.group(1)));
    }

    private String tradeDate(SqlDatasourceConfig datasource, LocalDate currentDate, int offset) {
        int today = Integer.parseInt(formatDate(currentDate));
        return String.valueOf(tradingCalendar(datasource).find(today, offset));
    }

    private int tradeOffset(String token) {
        if ("trade_date".equals(token)) {
            return 0;
        }
        return Integer.parseInt(token.substring("trade_date".length()));
    }

    private TradingCalendar tradingCalendar(SqlDatasourceConfig datasource) {
        TradingCalendarSource source = tradingCalendarSource(datasource);
        if (source.datasource() == null || source.datasource().getId() == null || source.datasource().getId().isBlank()) {
            throw new IllegalArgumentException("trade_date dynamic parameter requires a datasource");
        }
        return tradingCalendar(source);
    }

    private TradingCalendar tradingCalendar(TradingCalendarSource source) {
        String key = source.datasource().getId() + "::" + source.sql();
        TradingCalendarCache cached = calendarCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.calendar();
        }
        TradingCalendar loaded = loadTradingCalendar(source);
        calendarCache.put(key, new TradingCalendarCache(loaded, now + CACHE_TTL_MILLIS));
        return loaded;
    }

    private TradingCalendarSource tradingCalendarSource(SqlDatasourceConfig fallbackDatasource) {
        if (tradingCalendarConfigService != null) {
            TradingCalendarConfig config = tradingCalendarConfigService.current();
            SqlDatasourceConfig configuredDatasource = tradingCalendarConfigService.datasource(config);
            if (configuredDatasource == null) {
                throw new IllegalArgumentException("请先配置交易日数据源模板，选择数据库资产并维护交易日查询 SQL");
            }
            return new TradingCalendarSource(configuredDatasource, firstText(config.getSqlTemplate(), TradingCalendarConfig.DEFAULT_SQL));
        }
        return new TradingCalendarSource(fallbackDatasource, TradingCalendarConfig.DEFAULT_SQL);
    }

    private TradingCalendar loadTradingCalendar(TradingCalendarSource source) {
        SqlDatasourceConfig datasource = source.datasource();
        try {
            DataSource dataSource = driverLoader.createDataSource(
                datasource.getJdbcUrl(),
                datasource.getUsername(),
                datasource.getPassword(),
                datasource.getDriverClass(),
                SqlDatasourceConfigService.normalizeDatabaseType(
                    datasource.getDatabaseType(),
                    datasource.getJdbcUrl(),
                    datasource.getDriverClass()
                )
            );
            List<Integer> naturalDays = new ArrayList<>();
            List<Integer> tradingDays = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(source.sql());
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    naturalDays.add(resultSet.getInt(1));
                    tradingDays.add(resultSet.getInt(2));
                }
            }
            if (naturalDays.isEmpty()) {
                throw new IllegalStateException("trading calendar query returned no rows");
            }
            log.info("Loaded {} trading day rows for datasource {}", naturalDays.size(), datasource.getId());
            return new TradingCalendar(toIntArray(naturalDays), toIntArray(tradingDays));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load trading days: " + ex.getMessage(), ex);
        }
    }

    private String firstText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private String formatDate(LocalDate date) {
        return date.format(DATE);
    }

    private record TradingCalendarCache(TradingCalendar calendar, long expiresAtMillis) {
    }

    private record TradingCalendarSource(SqlDatasourceConfig datasource, String sql) {
    }

    private record TradingCalendar(int[] naturalDays, int[] tradingDays) {
        int find(int date, int offset) {
            int index = Arrays.binarySearch(naturalDays, date);
            if (index < 0 || index >= naturalDays.length) {
                return date;
            }
            int currentTradingDay = tradingDays[index];
            int remaining = date != currentTradingDay && offset < 0
                ? Math.abs(offset) - 1
                : Math.abs(offset);
            while (remaining != 0) {
                index = offset > 0 ? index + 1 : index - 1;
                if (index < 0 || index >= naturalDays.length) {
                    break;
                }
                int nextTradingDay = tradingDays[index];
                if (currentTradingDay != nextTradingDay) {
                    currentTradingDay = nextTradingDay;
                    remaining--;
                }
            }
            return currentTradingDay;
        }
    }

    public record TradingDayDecision(LocalDate date, boolean tradingDay, int mappedTradingDay) {
    }
}
