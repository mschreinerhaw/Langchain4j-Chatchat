package com.chatchat.mcpserver.datacapability.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface TradingDayRepository extends JpaRepository<TradingDay, String> {
    List<TradingDay> findByMarketAndDateBetweenOrderByDate(String market, LocalDate start, LocalDate end);
    Optional<TradingDay> findFirstByMarketAndTradingTrueAndDateLessThanOrderByDateDesc(String market, LocalDate date);
    Optional<TradingDay> findFirstByMarketAndTradingTrueAndDateGreaterThanOrderByDateAsc(String market, LocalDate date);
}
