package com.chatchat.mcpserver.search.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryQueryVariantsTest {

    @Test
    void preservesNestedMultiIntentQueriesAsIndependentVariants() {
        List<String> variants = DiscoveryQueryVariants.from(Map.of(
            "intent", "查询客户 070200046604 的交易、资产和盈亏",
            "intentCandidates", List.of(
                Map.of("intent", "交易明细", "queries", List.of("trade history", "成交记录")),
                Map.of("intent", "资产盈亏", "queries", List.of("portfolio pnl", "持仓分析"))
            ),
            "retrievalSignals", List.of("客户资产")
        ));

        assertThat(variants).contains(
            "查询客户 070200046604 的交易 资产和盈亏",
            "交易明细", "trade history", "成交记录",
            "资产盈亏", "portfolio pnl", "持仓分析", "客户资产");
        assertThat(variants).noneMatch(value -> value.contains("queries="));
    }
}
