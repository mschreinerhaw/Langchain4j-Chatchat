package com.chatchat.mcpserver.sql;

import com.chatchat.tools.builtin.DatabaseToolProperties;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicDateParamServiceTest {

    private final DynamicDateParamService service = new DynamicDateParamService(
        new DynamicJdbcDriverLoader(new DatabaseToolProperties()),
        Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneId.of("Asia/Shanghai"))
    );

    @Test
    void resolvesNaturalDateAndMonthSqlPlaceholdersWithoutTradingCalendar() {
        String sql = service.resolveSqlPlaceholders(
            "select ${today} today_value, ${month} month_value, ${month_start} month_start, ${month_end} month_end",
            null
        );

        assertThat(sql).isEqualTo(
            "select 20260707 today_value, 202607 month_value, 20260701 month_start, 20260731 month_end"
        );
    }

    @Test
    void enrichesCurrentTradeDateAndResolvesTradeDateOffsetsFromCalendarTable() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:dynamic_trade_days;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create schema dsc_cfg");
            statement.execute("create table dsc_cfg.t_xtjyr (ZRR int primary key, JYR int not null)");
            statement.execute("insert into dsc_cfg.t_xtjyr (ZRR, JYR) values " +
                "(20260703, 20260703), " +
                "(20260704, 20260703), " +
                "(20260705, 20260703), " +
                "(20260706, 20260706), " +
                "(20260707, 20260707), " +
                "(20260708, 20260708)");
        }

        SqlDatasourceConfig datasource = datasource(jdbcUrl);
        Map<String, Object> parameters = service.enrichParameters(
            Map.of("previous_trade_date", "${trade_date-1}"),
            datasource,
            "select * from customer_asset where stat_date = :trade_date"
        );
        String sql = service.resolveSqlPlaceholders(
            "select * from customer_asset where stat_date = ${trade_date-1}",
            datasource
        );

        assertThat(parameters)
            .containsEntry("today", "20260707")
            .containsEntry("natural_date", "20260707")
            .containsEntry("month", "202607")
            .containsEntry("month_start", "20260701")
            .containsEntry("month_end", "20260731")
            .containsEntry("trade_date", "20260707")
            .containsEntry("previous_trade_date", "20260706");
        assertThat(sql).isEqualTo("select * from customer_asset where stat_date = 20260706");
    }

    @Test
    void checksTradingDayAndRejectsDatesMissingFromMcpCalendarResult() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:trading_day_check;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table trading_calendar (natural_day int primary key, trading_day int not null)");
            statement.execute("insert into trading_calendar values " +
                "(20260710, 20260710), (20260711, 20260710), (20260712, 20260710), (20260713, 20260713)");
        }
        SqlDatasourceConfig datasource = datasource(jdbcUrl);
        TradingCalendarConfig config = new TradingCalendarConfig();
        config.setEnabled(true);
        config.setDatasourceId(datasource.getId());
        config.setSqlTemplate("select natural_day, trading_day from trading_calendar order by natural_day");
        TradingCalendarConfigService configService = mock(TradingCalendarConfigService.class);
        when(configService.current()).thenReturn(config);
        when(configService.datasource(config)).thenReturn(datasource);
        DynamicDateParamService calendarService = new DynamicDateParamService(
            new DynamicJdbcDriverLoader(new DatabaseToolProperties()), configService,
            Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        assertThat(calendarService.checkTradingDay(LocalDate.of(2026, 7, 10)).tradingDay()).isTrue();
        assertThat(calendarService.checkTradingDay(LocalDate.of(2026, 7, 11)).tradingDay()).isFalse();
        assertThatThrownBy(() -> calendarService.checkTradingDay(LocalDate.of(2026, 7, 14)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不包含日期");
    }

    private SqlDatasourceConfig datasource(String jdbcUrl) {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-trade");
        datasource.setName("trade-day-db");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDatabaseType("h2");
        return datasource;
    }
}
