package com.chatchat.mcpserver.datacapability.calendar;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Entity @Table(name = "mcp_market_trading_day", uniqueConstraints = @UniqueConstraint(columnNames = {"market", "date"}))
@Getter @Setter
public class TradingDay {
    @Id @Column(length = 80) private String id;
    @Column(nullable = false, length = 32) private String market;
    @Column(nullable = false) private LocalDate date;
    @Column(nullable = false) private boolean trading;
    @Column(length = 500) private String holiday;
}
