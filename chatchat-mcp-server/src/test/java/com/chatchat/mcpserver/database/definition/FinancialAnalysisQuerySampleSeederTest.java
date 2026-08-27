package com.chatchat.mcpserver.database.definition;

import com.chatchat.runtime.market.analysis.FinancialAnalysisQuerySamples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialAnalysisQuerySampleSeederTest {

    @Test
    void createsEverySampleDisabledAndRecordsOneTimeSeedState() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(DatabaseQueryConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        ArgumentCaptor<DatabaseQueryConfig> captor = ArgumentCaptor.forClass(DatabaseQueryConfig.class);
        verify(repository, times(7)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(config -> {
            assertThat(config.isEnabled()).isFalse();
            assertThat(config.getDatasourceId()).isEqualTo(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
            assertThat(config.getDescription()).isNotBlank();
            assertThat(config.getImplementationSteps()).isNotBlank();
            assertThat(config.getSqlStepsJson()).contains("resultSemantic", "modelUsage");
            assertThat(config.getInputSchemaJson()).contains("additionalProperties");
            assertThat(config.getGovernanceJson()).contains("evidencePolicy", "limitations");
            assertThat(config.getRiskLevel()).isEqualTo("read_only");
            assertThat(config.getOwner()).isEqualTo("system");
        });
        verify(jdbc, times(7)).update(
            org.mockito.ArgumentMatchers.contains("insert into market_analysis_sample_seed_state"),
            anyString(), any(java.sql.Timestamp.class));
    }

    @Test
    void doesNotRecreateSamplesWhoseSeedStateAlreadyExists() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        verify(repository, times(0)).save(any(DatabaseQueryConfig.class));
        verify(jdbc, times(0)).update(anyString(), any(), any());
    }

    @Test
    void preservesPublishedSystemMetadataAfterTheOneTimeSeed() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        DatabaseQueryConfig existing = new DatabaseQueryConfig();
        existing.setId("builtin-market-latest-movers");
        existing.setDatasourceId(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
        existing.setOwner("system");
        existing.setEnabled(true);
        existing.setSqlTemplate("""
            SELECT observation_date, quote_code
            FROM market_quote_daily
            QUALIFY ROW_NUMBER() OVER (
                PARTITION BY observation_date, source_code, source_url
                ORDER BY collected_at DESC, id DESC
            ) = 1
            """);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById("builtin-market-latest-movers")).thenReturn(Optional.of(existing));
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        verify(repository, times(0)).save(any(DatabaseQueryConfig.class));
        assertThat(existing.getSqlTemplate()).contains("QUALIFY ");
        verify(jdbc, times(0)).update(anyString(), any(), any());
    }

    @Test
    void preservesUserOwnedTemplatesEvenWhenTheirSqlDiffersFromTheBuiltinDefinition() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        DatabaseQueryConfig existing = new DatabaseQueryConfig();
        existing.setId("builtin-market-latest-movers");
        existing.setDatasourceId(FinancialAnalysisQuerySamples.INTERNAL_DATASOURCE_ID);
        existing.setOwner("data-admin");
        existing.setSqlTemplate("SELECT quote_code FROM market_quote_daily");
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        verify(repository, times(0)).save(any(DatabaseQueryConfig.class));
        assertThat(existing.getSqlTemplate()).isEqualTo("SELECT quote_code FROM market_quote_daily");
    }

    @Test
    void deletesRetiredFreshnessSampleFromExistingRegistries() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DatabaseQueryConfig retired = new DatabaseQueryConfig();
        retired.setId("builtin-market-dataset-freshness");
        retired.setToolName("sample_financial_dataset_freshness");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        when(repository.findById(retired.getId())).thenReturn(Optional.of(retired));
        when(repository.findByToolNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.findByToolNameIgnoreCase(retired.getToolName())).thenReturn(Optional.of(retired));
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        verify(repository).delete(retired);
        verify(repository, times(0)).save(any(DatabaseQueryConfig.class));
    }
}
