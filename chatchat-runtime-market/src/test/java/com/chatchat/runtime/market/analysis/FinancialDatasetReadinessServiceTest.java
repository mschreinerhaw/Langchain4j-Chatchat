package com.chatchat.runtime.market.analysis;

import com.chatchat.runtime.market.storage.FinancialReadOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialDatasetReadinessServiceTest {

    @Test
    void requiresSuccessfulCatalogReceiptForEveryReferencedDataset() {
        var dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:financial_readiness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table market_asset_catalog(table_name varchar(128),archive_table_name varchar(128),"
            + "last_observation_date date,last_collected_at timestamp)");
        FinancialReadOperations reads = (sql, arguments) -> jdbc.queryForList(sql, arguments);
        var beans = new StaticListableBeanFactory();
        beans.addBean("financialReadOperations", reads);
        var service = new FinancialDatasetReadinessService(beans.getBeanProvider(FinancialReadOperations.class));

        var missing = service.inspect("select quote_code from market_quote_daily");

        assertThat(missing.ready()).isFalse();
        assertThat(missing.status()).isEqualTo("DATASET_NOT_COLLECTED");
        assertThat(missing.missingTables()).containsExactly("market_quote_daily");

        Instant collectedAt = Instant.parse("2026-08-16T01:00:00Z");
        jdbc.update("insert into market_asset_catalog values (?,?,?,?)",
            "market_quote_daily", "market_quote_daily_weekly_snapshot",
            java.sql.Date.valueOf("2026-08-15"), Timestamp.from(collectedAt));

        var ready = service.inspect("select quote_code from market_quote_daily");
        assertThat(ready.ready()).isTrue();
        assertThat(ready.status()).isEqualTo("READY");
        assertThat(ready.lastCollectedAt()).isEqualTo(collectedAt);

        var archiveReady = service.inspect("select quote_code from market_quote_daily_weekly_snapshot");
        assertThat(archiveReady.ready()).isTrue();
    }

    @Test
    void reportsUnavailableWhenFinancialReadStorageBeanIsMissing() {
        var beans = new StaticListableBeanFactory();
        var service = new FinancialDatasetReadinessService(beans.getBeanProvider(FinancialReadOperations.class));

        var readiness = service.inspect("select * from market_quote_daily");

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.status()).isEqualTo("FINANCIAL_STORAGE_UNAVAILABLE");
        assertThat(readiness.missingTables()).containsExactly("market_quote_daily");
    }
}
