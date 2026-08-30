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
            .contains("Cover every successful non-empty dataset")
            .contains("do not claim that a type of record is missing")
            .contains("objective-aspect coverage matrix")
            .contains("one-period observation or small sample")
            .contains("do not reproduce complete record tables")
            .contains("livedata_orders summary")
            .doesNotContain("Executed plan attempts");
    }
}
