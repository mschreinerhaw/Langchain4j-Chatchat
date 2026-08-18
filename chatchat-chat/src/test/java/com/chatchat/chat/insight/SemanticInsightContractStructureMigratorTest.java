package com.chatchat.chat.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticInsightContractStructureMigratorTest {

    private final SemanticInsightContractRepository contracts = mock(SemanticInsightContractRepository.class);
    private final SemanticInsightFieldRepository fields = mock(SemanticInsightFieldRepository.class);
    private final SemanticInsightRecipeRepository recipes = mock(SemanticInsightRecipeRepository.class);
    private final SemanticInsightRecipeParameterRepository parameters =
        mock(SemanticInsightRecipeParameterRepository.class);
    private final SemanticInsightContractStructureMigrator migrator =
        new SemanticInsightContractStructureMigrator(
            contracts, fields, recipes, parameters, new ObjectMapper());

    @Test
    void mapsSensitiveFlagToMysqlCompatiblePhysicalColumn() throws NoSuchFieldException {
        Column column = SemanticInsightFieldEntity.class.getDeclaredField("sensitive")
            .getAnnotation(Column.class);

        assertThat(column.name()).isEqualTo("sensitive_flag");
        assertThat(column.nullable()).isFalse();
    }

    @Test
    void materializesReadableTypedRowsWithoutDeletingLegacySnapshot() {
        SemanticInsightContractEntity header = header("contract-a");
        header.setContractJson("""
            {
              "datasetAlias":"snapshot",
              "fields":[{
                "field":"RAW_VALUE","semantic":"measure","label":"Measured value",
                "unit":"items","aggregation":"SUM","sensitive":false
              }],
              "recipes":[{
                "id":"measure-ranking","operator":"TOP_N","label":"Measure ranking",
                "valueMetric":"measure","groupBy":"entity","topN":8,"absoluteValues":true,
                "presentationMode":"SUPPORTING","conclusionEligible":false,
                "presentationPriority":30,"section":"details",
                "relevanceHint":"Use only when totals are requested"
              }]
            }
            """);
        when(contracts.findAll()).thenReturn(List.of(header));
        when(fields.existsByContractId("contract-a")).thenReturn(false);
        when(recipes.existsByContractId("contract-a")).thenReturn(false);

        migrator.run(new DefaultApplicationArguments(new String[0]));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SemanticInsightFieldEntity>> fieldRows = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SemanticInsightRecipeEntity>> recipeRows = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SemanticInsightRecipeParameterEntity>> parameterRows =
            ArgumentCaptor.forClass(List.class);
        verify(fields).saveAll(fieldRows.capture());
        verify(recipes).saveAll(recipeRows.capture());
        verify(parameters).saveAll(parameterRows.capture());

        assertThat(fieldRows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getPhysicalField()).isEqualTo("RAW_VALUE");
            assertThat(row.getSemanticKey()).isEqualTo("measure");
            assertThat(row.getDisplayLabel()).isEqualTo("Measured value");
            assertThat(row.getAggregation()).isEqualTo("SUM");
        });
        assertThat(recipeRows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getRecipeKey()).isEqualTo("measure-ranking");
            assertThat(row.getPresentationMode()).isEqualTo("SUPPORTING");
            assertThat(row.isConclusionEligible()).isFalse();
            assertThat(row.getPresentationPriority()).isEqualTo(30);
            assertThat(row.getRelevanceHint()).contains("totals");
        });
        assertThat(parameterRows.getValue()).extracting(
            SemanticInsightRecipeParameterEntity::getParameterKey,
            SemanticInsightRecipeParameterEntity::getValueType)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("valueMetric", "STRING"),
                org.assertj.core.groups.Tuple.tuple("groupBy", "STRING"),
                org.assertj.core.groups.Tuple.tuple("topN", "INTEGER"),
                org.assertj.core.groups.Tuple.tuple("absoluteValues", "BOOLEAN"));
        assertThat(header.getDatasetAlias()).isEqualTo("snapshot");
        assertThat(header.getContractJson()).contains("RAW_VALUE");
    }

    @Test
    void skipsContractThatAlreadyHasStructuredRows() {
        SemanticInsightContractEntity header = header("contract-a");
        header.setContractJson("{\"fields\":[]}");
        when(contracts.findAll()).thenReturn(List.of(header));
        when(fields.existsByContractId("contract-a")).thenReturn(true);

        migrator.run(new DefaultApplicationArguments(new String[0]));

        verify(fields, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(recipes, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(parameters, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    private SemanticInsightContractEntity header(String id) {
        SemanticInsightContractEntity header = new SemanticInsightContractEntity();
        header.setContractId(id); header.setTenantId("tenant-a");
        header.setContractKey("snapshot-contract"); header.setContractVersion("1");
        header.setStatus("PUBLISHED"); header.setEnabled(true);
        return header;
    }
}
