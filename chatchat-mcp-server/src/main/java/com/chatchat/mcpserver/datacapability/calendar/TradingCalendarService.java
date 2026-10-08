package com.chatchat.mcpserver.datacapability.calendar;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@Service @RequiredArgsConstructor
public class TradingCalendarService {
    private final TradingDayRepository repository;
    public static String market(String market) {
        if (market == null || !market.matches("[A-Z][A-Z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid market code");
        return market;
    }
    @Transactional public List<TradingDay> save(List<DayRequest> days) {
        if (days == null || days.isEmpty() || days.size() > 10000) throw new IllegalArgumentException("Supply 1-10000 calendar days");
        Set<String> ids = new HashSet<>();
        List<TradingDay> entities = days.stream().map(request -> {
            TradingDay day = new TradingDay();
            day.setMarket(market(request.market()));
            if (request.date() == null || request.trading() == null) throw new IllegalArgumentException("date and trading are required");
            if (request.holiday() != null && request.holiday().length() > 500) throw new IllegalArgumentException("Holiday is too long");
            day.setDate(request.date()); day.setTrading(request.trading()); day.setHoliday(request.holiday());
            day.setId(day.getMarket() + ":" + day.getDate());
            if (!ids.add(day.getId())) throw new IllegalArgumentException("Duplicate market/date in batch: " + day.getId());
            return day;
        }).toList();
        return repository.saveAll(entities);
    }
    public List<TradingDay> range(String market, LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end) || start.plusYears(10).isBefore(end))
            throw new IllegalArgumentException("Invalid calendar range (maximum 10 years)");
        return repository.findByMarketAndDateBetweenOrderByDate(market(market), start, end);
    }
    public TradingDay day(String market, LocalDate date) {
        return repository.findById(market(market) + ":" + date)
            .orElseThrow(() -> new IllegalArgumentException("Calendar date has not been maintained"));
    }
    public TradingDay adjacent(String market, LocalDate date, boolean next) {
        day(market, date);
        TradingDay found = (next ? repository.findFirstByMarketAndTradingTrueAndDateGreaterThanOrderByDateAsc(market, date)
            : repository.findFirstByMarketAndTradingTrueAndDateLessThanOrderByDateDesc(market, date))
            .orElseThrow(() -> new IllegalArgumentException("No adjacent trading day in maintained calendar"));
        LocalDate start = next ? date : found.getDate();
        LocalDate end = next ? found.getDate() : date;
        long expected = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (range(market, start, end).size() != expected) throw new IllegalArgumentException("Calendar coverage contains gaps");
        return found;
    }
    public record DayRequest(String market, LocalDate date, Boolean trading, String holiday) {}
}
