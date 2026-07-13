package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMetadataGroundingGuardTest {

    private final SqlMetadataGroundingGuard guard = new SqlMetadataGroundingGuard();
    private final Map<String, Object> facts = Map.of(
        "evidenceIdentifiers", List.of("finance", "public", "customer_return_fact", "public.customer_return_fact", "customer_id", "return_rate")
    );

    @Test
    void acceptsSummaryThatUsesOnlyRetrievedIdentifiers() {
        assertThat(guard.violations(
            "检索到 `public.customer_return_fact`，可使用 `customer_id` 与 `return_rate` 分析客户收益率。",
            facts
        )).isEmpty();
    }

    @Test
    void rejectsInventedTablesAndInferredWarehouseLayers() {
        assertThat(guard.violations(
            "建议使用 DWS 层的 `dws_cust_return_monthly`，并补充推荐常见表。",
            facts
        )).contains(
            "inferred_database_layer:DWS 层",
            "unsupported_identifier:dws_cust_return_monthly"
        ).anyMatch(value -> value.startsWith("unsupported_inference_phrase:"));
    }
}
