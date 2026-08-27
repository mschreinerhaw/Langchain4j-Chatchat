package com.chatchat.agents.runtime.config;


import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimePropertiesTest {

    @Test
    void configuresSparkStyleWorkerRetriesWithThreeRetriesByDefault() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();

        assertThat(properties.analysisSummaryWorkerMaxRetries()).isEqualTo(3);

        properties.setAnalysisSummaryWorkerMaxRetries(-1);
        assertThat(properties.analysisSummaryWorkerMaxRetries()).isZero();

        properties.setAnalysisSummaryWorkerMaxRetries(99);
        assertThat(properties.analysisSummaryWorkerMaxRetries()).isEqualTo(9);
    }

    @Test
    void configuresLosslessAnalysisChunkBoundariesWithoutZeroSizedChunks() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setRecordAnalysisChunkMaxChars(24_000);
        properties.setRecordAnalysisChunkMaxRows(125);

        assertThat(properties.recordAnalysisChunkMaxChars()).isEqualTo(24_000);
        assertThat(properties.recordAnalysisChunkMaxRows()).isEqualTo(125);

        properties.setRecordAnalysisChunkMaxChars(0);
        properties.setRecordAnalysisChunkMaxRows(0);

        assertThat(properties.recordAnalysisChunkMaxChars()).isEqualTo(1_000);
        assertThat(properties.recordAnalysisChunkMaxRows()).isEqualTo(1);
    }

    @Test
    void configuresAnalysisSpillWithSafeDefaultsAndBounds() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();

        assertThat(properties.isAnalysisSpillEnabled()).isTrue();
        assertThat(properties.analysisSpillRocksDbPath())
            .isEqualTo("./data/agent-analysis-spill-rocksdb");
        assertThat(properties.analysisSpillThresholdBytes()).isEqualTo(65_536);
        assertThat(properties.analysisSpillTtlMs()).isEqualTo(7L * 24 * 60 * 60 * 1000);

        properties.setAnalysisSpillThresholdBytes(1);
        properties.setAnalysisSpillTtlMs(-1);
        properties.setAnalysisSpillRocksDbPath(" ");

        assertThat(properties.analysisSpillThresholdBytes()).isEqualTo(1_024);
        assertThat(properties.analysisSpillTtlMs()).isZero();
        assertThat(properties.analysisSpillRocksDbPath())
            .isEqualTo("./data/agent-analysis-spill-rocksdb");
    }
}
