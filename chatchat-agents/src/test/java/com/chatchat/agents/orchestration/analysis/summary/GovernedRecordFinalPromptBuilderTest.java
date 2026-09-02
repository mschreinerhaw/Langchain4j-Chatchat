package com.chatchat.agents.orchestration.analysis.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GovernedRecordFinalPromptBuilderTest {

    @Test
    void makesPerDatasetContractsAuthoritativeAndRejectsFalseMissingDataClaims() {
        String prompt = GovernedRecordFinalPromptBuilder.build(
            "分析客户交易偏好", "遵守业务口径",
            "livedata_assets summary\nlivedata_orders summary\nlivedata_profit summary");

        assertThat(prompt)
            .contains("workerAnalysisContext and templateMatchAnalysis")
            .contains("agent_role_analysis_context attached to governed inputs")
            .contains("management-level Driver reviewing completed Worker analysis reports")
            .contains("do not restart from raw rows")
            .contains("perform a management review of the Worker analyses")
            .contains("specific improvement suggestions and prioritized next work directions")
            .contains("business description, business scenarios and tags")
            .contains("Account for every successful non-empty dataset")
            .contains("omit irrelevant, rejected or merely catalog-like results")
            .contains("never by query source, tool, search channel, chunk or execution view")
            .contains("do not claim that a type of record is missing")
            .contains("objective-aspect coverage matrix")
            .contains("one-period observation or small sample")
            .contains("A table of values, configuration inventory")
            .contains("current state, declared baseline/comparable reference, material deviation")
            .contains("do not present cumulative counters as current rates")
            .contains("producer-returned metric at its declared grain is an observation")
            .contains("do not claim that aggregation authorization is missing")
            .contains("do not reproduce complete record tables")
            .contains("livedata_orders summary")
            .doesNotContain("Executed plan attempts");
    }
}
