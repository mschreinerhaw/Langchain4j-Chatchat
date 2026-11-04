package com.chatchat.mcpserver.market;

import com.chatchat.runtime.market.storage.FinancialWriteStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SnapshotFinancialReadOperationsRecoveryTest {
    @Test
    void concurrentClosedReadersReopenTheSameImmutableSnapshotOnlyOnce(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            var stale = mock(FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations.class);
            var entered = new CountDownLatch(2);
            when(stale.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
                entered.countDown();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                throw closed();
            });
            fixture.replaceReader(stale);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> fixture.reads.queryForList("select quote_name from market_quote_daily where id=?", 1));
                var second = executor.submit(() -> fixture.reads.queryForList("select quote_name from market_quote_daily where id=?", 1));
                for (var future : java.util.List.of(first, second))
                    assertThat(future.get(10, TimeUnit.SECONDS)).singleElement()
                        .satisfies(row -> assertThat(row).containsEntry("quote_name", "Stable"));
                verify(stale, times(1)).close();
                assertThat(fixture.reads.activeSlot()).isEqualTo("A");
                assertThat(fixture.reads.generation()).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void namedReadsRecoverWithoutChangingTheirRowLimitOrTheWriter(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            var stale = mock(FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations.class);
            when(stale.queryForList(anyString(), anyMap(), anyInt(), anyInt())).thenThrow(closed());
            fixture.replaceReader(stale);
            assertThat(fixture.reads.queryForList("select quote_name from market_quote_daily where id>=:id order by id",
                Map.of("id", 1), 1, 2)).singleElement().satisfies(row -> assertThat(row).containsEntry("quote_name", "Stable"));
            verify(stale).close();
            assertThat(fixture.writer.jdbc().queryForObject("select count(*) from market_quote_daily", Integer.class)).isEqualTo(2);
            assertThat(fixture.reads.generation()).isEqualTo(1);
        }
    }

    @Test
    void cancelledOrInvalidQueriesAreNotRetriedAndCannotRebuildThePool(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root)) {
            var stale = mock(FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations.class);
            var timeout = new DataAccessResourceFailureException("deadline", new SQLException("cancelled", "57014", 57014));
            when(stale.queryForList(anyString(), any(Object[].class))).thenThrow(timeout);
            fixture.replaceReader(stale);
            assertThatThrownBy(() -> fixture.reads.queryForList("select quote_name from market_quote_daily where id=?", 1))
                .isSameAs(timeout);
            verify(stale, never()).close();
            verify(stale, times(1)).queryForList(anyString(), any(Object[].class));
        }
    }

    private static DataAccessResourceFailureException closed() {
        return new DataAccessResourceFailureException("closed snapshot",
            new SQLException("The database has been closed", "90098", org.h2.api.ErrorCode.DATABASE_IS_CLOSED));
    }

    private static final class Fixture implements AutoCloseable {
        final FinancialWriteStorage writer;
        final SnapshotFinancialReadOperations reads;

        Fixture(Path root) {
            var properties = new FinancialQueryPoolProperties();
            properties.setStorage("LOCAL_H2");
            properties.setLocalJdbcUrl("jdbc:h2:file:" + root.resolve("writer/financial-market").toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
            properties.setSnapshotRoot(root.resolve("snapshots").toString());
            properties.setMaximumPoolSize(2);
            var configuration = new FinancialQueryPoolConfiguration();
            writer = configuration.financialWriteStorage(properties);
            reads = configuration.snapshotFinancialReadOperations(writer, properties);
            writer.jdbc().execute("create table market_asset_catalog(dataset_code varchar(64) primary key)");
            writer.jdbc().execute("create table market_quote_daily(id integer primary key, quote_name varchar(64))");
            writer.jdbc().update("insert into market_quote_daily values (1,'Stable'),(2,'Second')");
            reads.publish();
            writer.jdbc().update("update market_quote_daily set quote_name='Unpublished' where id=1");
        }

        void replaceReader(FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations stale) throws Exception {
            var field = SnapshotFinancialReadOperations.class.getDeclaredField("active");
            field.setAccessible(true);
            ((FinancialQueryPoolConfiguration.IsolatedFinancialReadOperations) field.get(reads)).close();
            field.set(reads, stale);
        }

        @Override public void close() {
            reads.close();
            writer.close();
        }
    }
}
