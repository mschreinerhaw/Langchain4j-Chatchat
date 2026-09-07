package com.chatchat.api.datascience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonEnvironmentStatusTest {

    @Test
    void defaultsToReadyUnlessMcpExplicitlyReportsAnAbnormalState() {
        assertThat(PythonDataScienceService.effectiveAssetStatus(null)).isEqualTo("READY");
        assertThat(PythonDataScienceService.effectiveAssetStatus("")).isEqualTo("READY");
        assertThat(PythonDataScienceService.effectiveAssetStatus("PUBLISHED")).isEqualTo("READY");
        assertThat(PythonDataScienceService.effectiveAssetStatus("READY")).isEqualTo("READY");
        assertThat(PythonDataScienceService.effectiveAssetStatus("DRAFT")).isEqualTo("PROVISIONING");
        assertThat(PythonDataScienceService.effectiveAssetStatus("PROVISIONING")).isEqualTo("PROVISIONING");

        assertThat(PythonDataScienceService.effectiveAssetStatus("DISABLED")).isEqualTo("DISABLED");
        assertThat(PythonDataScienceService.effectiveAssetStatus("FAILED")).isEqualTo("FAILED");
        assertThat(PythonDataScienceService.effectiveAssetStatus("ERROR")).isEqualTo("FAILED");
        assertThat(PythonDataScienceService.effectiveAssetStatus("UNAVAILABLE")).isEqualTo("FAILED");
    }
}
