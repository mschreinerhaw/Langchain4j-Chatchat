package com.chatchat.mcpserver.news.tool;

import com.chatchat.mcpserver.news.financial.FinancialEnrichmentService;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialDataMcpToolProviderTest {

    @Test
    @SuppressWarnings("unchecked")
    void explicitToolDynamicallyDiscoversAndReturnsOnlyLocalCollectedFinancialData() {
        FinancialEnrichmentService financial = mock(FinancialEnrichmentService.class);
        String query = "runtime supplied opening analysis question";
        when(financial.enrich(eq(query), any(), eq(3))).thenReturn(
            new FinancialEnrichmentService.EnrichmentResult(query,
                List.of(Map.of("dataset_code", "runtime_discovered_dataset")),
                List.of(Map.of("dataset", "runtime_discovered_dataset", "rows", List.of(Map.of(
                    "metric", "breadth", "value", 12, "payload_json", "must-not-enter-model-context")))),
                List.of(), null));
        FinancialDataMcpToolProvider provider = new FinancialDataMcpToolProvider(financial);

        ToolOutput output = provider.findExecutor(FinancialDataMcpToolProvider.TOOL_NAME).orElseThrow()
            .execute(ToolInput.builder().parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        assertThat(data).containsEntry("retrievalSource", "governed_financial_store")
            .containsEntry("networkSearchUsed", false)
            .containsEntry("datasetCount", 1)
            .containsEntry("observationCount", 1);
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) data.get("financialData");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) datasets.get(0).get("rows");
        assertThat(rows.get(0)).doesNotContainKey("payload_json").containsKey("_omitted_fields");
        ArgumentCaptor<ToolInput> input = ArgumentCaptor.forClass(ToolInput.class);
        verify(financial).enrich(eq(query), input.capture(), eq(3));
        assertThat(input.getValue().getParameters()).containsEntry("financial_data_required", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactDatasetModeUsesRuntimeDatasetArgumentWithoutEmbeddedDatasetNames() {
        FinancialEnrichmentService financial = mock(FinancialEnrichmentService.class);
        String dataset = "new_dataset_registered_after_release";
        when(financial.queryDataset(eq(dataset), any())).thenReturn(Map.of(
            "rows", List.of(Map.of("observation_date", "2026-08-01", "value", 9))));
        FinancialDataMcpToolProvider provider = new FinancialDataMcpToolProvider(financial);

        ToolOutput output = provider.findExecutor(FinancialDataMcpToolProvider.TOOL_NAME).orElseThrow()
            .execute(ToolInput.builder().parameters(Map.of("dataset", dataset, "limit", 10)).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("mode", "EXACT_DATASET_QUERY")
            .containsEntry("dataset", dataset)
            .containsEntry("observationCount", 1);
        verify(financial).queryDataset(eq(dataset), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyRuntimeCatalogIsAUsablePartialResultInsteadOfAProtocolFailure() {
        FinancialEnrichmentService financial = mock(FinancialEnrichmentService.class);
        String query = "runtime question with no locally collected observations";
        when(financial.enrich(eq(query), any(), eq(3))).thenReturn(
            new FinancialEnrichmentService.EnrichmentResult(query, List.of(), List.of(),
                List.of("no governed dataset matched the current intent"), "financial_data_unavailable"));
        FinancialDataMcpToolProvider provider = new FinancialDataMcpToolProvider(financial);

        ToolOutput output = provider.findExecutor(FinancialDataMcpToolProvider.TOOL_NAME).orElseThrow()
            .execute(ToolInput.builder().parameters(Map.of("query", query)).build());

        assertThat(output.isSuccess()).isTrue();
        assertThat((Map<String, Object>) output.getData())
            .containsEntry("datasetCount", 0)
            .containsEntry("observationCount", 0)
            .containsEntry("coverageComplete", false)
            .containsEntry("skippedReason", "financial_data_unavailable")
            .containsKey("warnings");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizedRuntimeFieldIsOmittedAndRequestedRowLimitCannotEscapeContractMaximum() {
        FinancialEnrichmentService financial = mock(FinancialEnrichmentService.class);
        String dataset = "runtime_extreme_dataset";
        String oversized = "x".repeat(2_000_000);
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 250)
            .mapToObj(index -> Map.<String, Object>of(
                "sequence", index, "metric", "value-" + index, "raw_document", oversized))
            .toList();
        when(financial.queryDataset(eq(dataset), any())).thenReturn(Map.of("rows", rows));
        FinancialDataMcpToolProvider provider = new FinancialDataMcpToolProvider(financial);

        ToolOutput output = provider.findExecutor(FinancialDataMcpToolProvider.TOOL_NAME).orElseThrow()
            .execute(ToolInput.builder().parameters(Map.of("dataset", dataset, "limit", 100_000)).build());

        assertThat(output.isSuccess()).isTrue();
        Map<String, Object> data = (Map<String, Object>) output.getData();
        Map<String, Object> datasetResult = (Map<String, Object>)
            ((List<?>) data.get("financialData")).get(0);
        List<Map<String, Object>> compact = (List<Map<String, Object>>) datasetResult.get("rows");
        assertThat(compact).hasSize(200).allSatisfy(row -> {
            assertThat(row).doesNotContainKey("raw_document");
            assertThat((List<String>) row.get("_omitted_fields")).contains("raw_document");
        });
        assertThat(data).containsEntry("observationCount", 200);
    }

    @Test
    void toolContractMakesLocalAndWebResponsibilitiesExplicit() {
        FinancialDataMcpToolProvider provider = new FinancialDataMcpToolProvider(
            mock(FinancialEnrichmentService.class));

        assertThat(provider.definitions()).singleElement().satisfies(definition -> {
            assertThat(definition.name()).isEqualTo("financial_data_search");
            assertThat(definition.description()).contains("never searches the public web")
                .contains("Never invent or hardcode dataset codes");
            assertThat(definition.parameters()).extracting("name")
                .contains("query", "dataset", "filters", "startDate", "endDate");
        });
    }
}
