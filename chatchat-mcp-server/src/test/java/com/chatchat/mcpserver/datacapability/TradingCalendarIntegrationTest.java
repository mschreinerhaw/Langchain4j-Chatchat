package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.calendar.*;
import com.chatchat.mcpserver.datacapability.definition.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TradingCalendarIntegrationTest {
    private static LocalContainerEntityManagerFactoryBean factory;
    private EntityManager em;
    private TradingCalendarService calendar;
    @BeforeAll static void database() {
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(new DriverManagerDataSource("jdbc:h2:mem:capability_calendar;DB_CLOSE_DELAY=-1", "sa", ""));
        factory.setPackagesToScan("com.chatchat.mcpserver.datacapability.calendar");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
    }
    @BeforeEach void start() {
        em = factory.getObject().createEntityManager(); em.getTransaction().begin();
        calendar = new TradingCalendarService(new JpaRepositoryFactory(em).getRepository(TradingDayRepository.class));
    }
    @AfterEach void rollback() { em.getTransaction().rollback(); em.close(); }
    @AfterAll static void close() { factory.destroy(); }
    private TradingCalendarService.DayRequest day(String market, String date, boolean trading) {
        return new TradingCalendarService.DayRequest(market, LocalDate.parse(date), trading, trading ? null : "休市");
    }
    @Test void storesHolidaysAndQueriesStrictPreviousNextAndMarketIsolation() {
        calendar.save(List.of(day("SSE", "2026-10-09", true), day("SSE", "2026-10-10", false),
            day("SSE", "2026-10-11", false), day("SSE", "2026-10-12", true), day("HKEX", "2026-10-10", true)));
        em.flush(); em.clear();
        LocalDate saturday = LocalDate.parse("2026-10-10");
        assertThat(calendar.day("SSE", saturday).isTrading()).isFalse();
        assertThat(calendar.day("HKEX", saturday).isTrading()).isTrue();
        assertThat(calendar.adjacent("SSE", saturday, false).getDate()).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(calendar.adjacent("SSE", saturday, true).getDate()).isEqualTo(LocalDate.parse("2026-10-12"));
        var d = new CapabilityDefinition("calendar_range", "日历", null, CapabilityType.TRADING_CALENDAR, null, null,
            "tradingDays", null, null, Map.of("market", "SSE"), 5, 10, true, true, true);
        var result = new TradingCalendarAdapter(calendar).execute(d, Map.of("start", "2026-10-09", "end", "2026-10-12"));
        assertThat(result.rows()).extracting(r -> r.get("date")).containsExactly("2026-10-09", "2026-10-12");
    }
    @Test void rejectsMissingCoverageRatherThanInventingTradingDays() {
        calendar.save(List.of(day("SSE", "2026-10-09", true), day("SSE", "2026-10-12", true)));
        assertThatThrownBy(() -> calendar.day("SSE", LocalDate.parse("2026-10-10"))).hasMessageContaining("not been maintained");
        assertThatThrownBy(() -> calendar.adjacent("SSE", LocalDate.parse("2026-10-09"), true)).hasMessageContaining("gaps");
    }
    @Test void updatesExistingDateAndRejectsDuplicateRows() {
        calendar.save(List.of(day("SSE", "2026-10-09", false)));
        calendar.save(List.of(day("SSE", "2026-10-09", true)));
        em.flush(); em.clear();
        assertThat(calendar.day("SSE", LocalDate.parse("2026-10-09")).isTrading()).isTrue();
        assertThatThrownBy(() -> calendar.save(List.of(day("SSE", "2026-10-10", true), day("SSE", "2026-10-10", false))))
            .hasMessageContaining("Duplicate");
    }
}
