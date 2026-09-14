package com.chatchat.agents.orchestration.analysis.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiedReportDataCatalogTest {
    @Test void retainsAllDatasetIdentitiesWhenThePromptProjectionIsBounded() {
        List<ReturnedReportDataset> datasets = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            datasets.add(new ReturnedReportDataset("dataset-" + index,
                List.of(Map.of("value", index)), 1, true));
        }

        VerifiedReportDataCatalog catalog = VerifiedReportDataCatalog.fromRuntime(
            Map.of("runtimeReturnedReportDatasets", datasets));
        VerifiedReportDataCatalog.DatasetPromptProjection projection =
            catalog.datasetPromptProjection(1);

        assertThat(catalog.datasetCount()).isEqualTo(20);
        assertThat(projection.datasets()).isEmpty();
        assertThat(projection.omittedDatasetReferences()).containsExactlyInAnyOrderElementsOf(
            catalog.datasetReferences());
    }
}
