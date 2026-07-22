package com.chatchat.runtime.market.storage;

import com.chatchat.runtime.market.model.MarketObservation;
import com.chatchat.runtime.market.model.MarketSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialDataIngestionServiceTest {
    @Test
    void expandsThreeMarketSegmentsIntoIndividuallyQueryableRows() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        when(store.storeAll(any())).thenReturn(List.of(new FinancialDataStore.StoredObservation(
            "market_statistics_daily", "market_statistics_daily", "record",
            LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-22"))));
        var service = new FinancialDataIngestionService(store, catalog);
        var source = new MarketSource(9L, "three_market_overview", "沪深港市场汇总", "https://example.test");
        var item = new MarketObservation(source, "香港市场汇总", "content", null, "香港交易所",
            "https://example.test#hkex", Instant.parse("2026-07-20T00:00:00Z"), "zh-HK",
            List.of("沪深港市场汇总"), List.of("官方数据"), Map.of(
                "provider", "HKEX", "dataset", "沪深港市场汇总",
                "datasetCode", "market_statistics_daily", "tradeDate", "2026-07-20",
                "market", "香港交易所", "segments", List.of(
                    Map.of("segment", "主板", "listedCompanies", "2457"),
                    Map.of("segment", "创业板", "listedCompanies", "306"))));

        service.accept(item);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketObservation>> rows = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(rows.capture());
        assertThat(rows.getValue()).extracting(row -> row.metadata().get("segment"))
            .containsExactly("主板", "创业板");
        assertThat(rows.getValue()).allSatisfy(row -> assertThat(row.metadata()).doesNotContainKey("segments"));
        verify(catalog).index(FinancialDatasetDefinition.byCode("market_statistics_daily"));
    }

    @Test
    void expandsChinaBondCurvePointsWithStableMaturityIdentity() {
        FinancialDataStore store = mock(FinancialDataStore.class);
        FinancialAssetCatalogService catalog = mock(FinancialAssetCatalogService.class);
        when(store.storeAll(any())).thenReturn(List.of(new FinancialDataStore.StoredObservation(
            "bond_yield_curve_daily", "bond_yield_curve_daily", "record",
            LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-22"))));
        var service = new FinancialDataIngestionService(store, catalog);
        var source = new MarketSource(50L, "chinabond_home", "中国债券信息网数据分析", "https://www.chinabond.com.cn/");
        var item = new MarketObservation(source, "中债国债到期收益率", "content", null, "CCDC",
            "https://yield.chinabond.com.cn/#curve-2026-07-21", Instant.parse("2026-07-21T00:00:00Z"),
            "zh-CN", List.of("债券收益率"), List.of("中债"), Map.of(
                "provider", "CHINABOND", "dataset", "中债收益率曲线",
                "datasetCode", "bond_yield_curve_daily", "tradeDate", "2026-07-21",
                "curvePoints", List.of(
                    Map.of("maturityYears", "0.5", "yieldPct", "1.1032"),
                    Map.of("maturityYears", "1.0", "yieldPct", "1.1420"))));

        service.accept(item);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketObservation>> rows = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue()).extracting(row -> row.metadata().get("maturityYears"))
            .containsExactly("0.5", "1.0");
        assertThat(rows.getValue()).extracting(MarketObservation::sourceUrl)
            .containsExactly(
                "https://yield.chinabond.com.cn/#curve-2026-07-21#observation=0.5",
                "https://yield.chinabond.com.cn/#curve-2026-07-21#observation=1.0");
        assertThat(rows.getValue()).allSatisfy(row -> assertThat(row.metadata()).doesNotContainKey("curvePoints"));
        verify(catalog).index(FinancialDatasetDefinition.byCode("bond_yield_curve_daily"));
    }
}
