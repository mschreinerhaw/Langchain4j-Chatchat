package com.chatchat.common.mcp.calendar;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.time.LocalDate;

/** Trading-calendar decision boundary used by task scheduling policy. */
public interface McpTradingCalendarPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.trading_calendar.v1";

    TradingDayResult check(LocalDate date);

    record TradingDayResult(boolean tradingDay, String message) {
    }
}
