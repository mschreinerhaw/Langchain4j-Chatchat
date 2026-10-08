package com.chatchat.mcpserver.datacapability.calendar;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.CapabilityAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

@Component @RequiredArgsConstructor
public class TradingCalendarAdapter implements CapabilityAdapter {
    private final TradingCalendarService calendar;
    private static final Set<String> OPERATIONS = Set.of("isTradingDay", "previousTradingDay", "nextTradingDay", "tradingDays");
    @Override public CapabilityType type() { return CapabilityType.TRADING_CALENDAR; }
    @Override public void validate(CapabilityDefinition d) {
        TradingCalendarService.market(String.valueOf(d.options().getOrDefault("market", "")));
        if (!OPERATIONS.contains(d.query())) throw new IllegalArgumentException("Unsupported calendar operation");
    }
    @Override public QueryResult execute(CapabilityDefinition d, Map<String, Object> parameters) {
        String market = String.valueOf(d.options().get("market"));
        List<TradingDay> days;
        if ("tradingDays".equals(d.query())) {
            LocalDate start = date(parameters, "start"), end = date(parameters, "end");
            List<TradingDay> maintained = calendar.range(market, start, end);
            long expected = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            if (maintained.size() != expected) throw new IllegalArgumentException("Calendar coverage contains gaps");
            days = maintained.stream().filter(TradingDay::isTrading).toList();
        } else {
            LocalDate date = date(parameters, "date");
            days = List.of(switch (d.query()) {
                case "isTradingDay" -> calendar.day(market, date);
                case "previousTradingDay" -> calendar.adjacent(market, date, false);
                case "nextTradingDay" -> calendar.adjacent(market, date, true);
                default -> throw new IllegalArgumentException("Unsupported calendar operation");
            });
        }
        List<Map<String, Object>> rows = days.stream().limit(d.maxRows()).map(day -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("market", day.getMarket()); row.put("date", day.getDate().toString());
            row.put("trading", day.isTrading()); row.put("holiday", day.getHoliday());
            return row;
        }).toList();
        return new QueryResult(rows, days.size() > d.maxRows(), Map.of());
    }
    private LocalDate date(Map<String, Object> parameters, String key) {
        if (parameters.get(key) == null) throw new IllegalArgumentException("Missing date parameter: " + key);
        return LocalDate.parse(String.valueOf(parameters.get(key)));
    }
}
