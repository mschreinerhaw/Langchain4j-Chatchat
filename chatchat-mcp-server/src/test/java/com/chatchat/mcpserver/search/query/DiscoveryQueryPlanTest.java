package com.chatchat.mcpserver.search.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryQueryPlanTest {

    @Test
    void keepsEveryAnalyzedKeywordAsAnIndependentTraceableQueryUnit() {
        DiscoveryQueryPlan plan = DiscoveryQueryPlan.from(Map.of(
            "intent", "analyze customer transactions assets and profit",
            "keywords", List.of("trade history", "filled orders", "asset snapshot", "profit and loss"),
            "intentCandidates", List.of(
                Map.of("intent", "trading preference", "queries", List.of("holding period", "trade frequency"))
            )
        ));

        assertThat(plan.queries()).contains(
            "trade history", "filled orders", "asset snapshot", "profit and loss",
            "trading preference", "holding period", "trade frequency");
        assertThat(plan.queries()).doesNotContain(
            "trade history filled orders asset snapshot profit and loss");
        assertThat(plan.units()).filteredOn(unit -> unit.query().equals("trade history"))
            .singleElement()
            .satisfies(unit -> assertThat(unit.sourcePaths()).containsExactly("keywords[0]"));
        assertThat(plan.metadata())
            .containsEntry("schemaVersion", DiscoveryQueryPlan.SCHEMA_VERSION)
            .containsEntry("mode", "independent_query_units")
            .containsEntry("executionPolicy", "search_each_unit_independently");
    }

    @Test
    void deduplicatesEquivalentQueriesWithoutDiscardingTheirSources() {
        DiscoveryQueryPlan plan = DiscoveryQueryPlan.from(Map.of(
            "queryTerms", List.of("trade history"),
            "keywords", List.of("trade history")
        ));

        assertThat(plan.queries()).containsExactly("trade history");
        assertThat(plan.units().get(0).sourcePaths())
            .containsExactly("queryTerms[0]", "keywords[0]");
    }
}
