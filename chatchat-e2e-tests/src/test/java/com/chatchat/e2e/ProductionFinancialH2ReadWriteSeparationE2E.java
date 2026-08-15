package com.chatchat.e2e;

import com.chatchat.mcpserver.market.FinancialQueryPoolConfiguration;
import com.chatchat.mcpserver.market.FinancialQueryPoolProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Release gate for the local financial data plane used by production MCP searches. */
class ProductionFinancialH2ReadWriteSeparationE2E {

    @Test
    void collectedRowsRemainQueryableWhenTheControlPlaneDatabaseIsUnavailable(@TempDir Path tempDir) {
        FinancialQueryPoolProperties properties = new FinancialQueryPoolProperties();
        properties.setStorage("LOCAL_H2");
        String databasePath = tempDir.resolve("financial-market").toAbsolutePath().toString().replace('\\', '/');
        properties.setLocalJdbcUrl("jdbc:h2:file:" + databasePath + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        properties.setSnapshotRoot(tempDir.resolve("snapshots").toString());
        properties.setConnectionTimeoutMs(250);
        properties.setQueryTimeoutSeconds(2);

        FinancialQueryPoolConfiguration configuration = new FinancialQueryPoolConfiguration();
        try (var ingestionStorage = configuration.financialWriteStorage(properties);
             var onlineReads = configuration.snapshotFinancialReadOperations(ingestionStorage, properties)) {
            JdbcTemplate ingestion = ingestionStorage.jdbc();
            ingestion.execute("create table market_asset_catalog(dataset_code varchar(64) primary key)");
            ingestion.execute("create table market_quote_daily("
                + "quote_code varchar(16) primary key,trade_date date,close decimal(18,4),volume bigint)");
            ingestion.update("insert into market_quote_daily values (?,?,?,?)",
                "000001", java.sql.Date.valueOf("2026-08-15"), 3701.23, 588_000_000L);
            onlineReads.publish();

            assertThat(onlineReads.activeSlot()).isEqualTo("A");
            assertThat(onlineReads.queryForList(
                "select quote_code,trade_date,close,volume from market_quote_daily where trade_date=?",
                java.sql.Date.valueOf("2026-08-15")))
                .singleElement().satisfies(row -> assertThat(row)
                    .containsEntry("quote_code", "000001")
                    .containsEntry("volume", 588_000_000L));

            ingestion.update("update market_quote_daily set volume=? where quote_code=?",
                600_000_000L, "000001");
            assertThat(onlineReads.queryForList(
                "select volume from market_quote_daily where quote_code=?", "000001"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("volume", 588_000_000L));
            onlineReads.publish();

            assertThat(onlineReads.activeSlot()).isEqualTo("B");
            assertThat(onlineReads.generation()).isEqualTo(2);
            assertThat(onlineReads.queryForList(
                "select volume from market_quote_daily where quote_code=?", "000001"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("volume", 600_000_000L));
            assertThatThrownBy(() -> onlineReads.queryForList("delete from market_quote_daily"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only accepts SELECT/WITH");
        }
    }
}
