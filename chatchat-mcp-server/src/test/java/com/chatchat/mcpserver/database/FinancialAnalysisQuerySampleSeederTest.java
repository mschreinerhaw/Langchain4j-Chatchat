package com.chatchat.mcpserver.database;

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
        verify(repository, times(8)).save(captor.capture());
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
        verify(jdbc, times(8)).update(
            org.mockito.ArgumentMatchers.contains("insert into market_analysis_sample_seed_state"),
            anyString(), any(java.sql.Timestamp.class));
    }

    @Test
    void doesNotRecreateSamplesWhoseSeedStateAlreadyExists() {
        DatabaseQueryConfigRepository repository = mock(DatabaseQueryConfigRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        FinancialAnalysisQuerySampleSeeder seeder =
            new FinancialAnalysisQuerySampleSeeder(repository, jdbc, new ObjectMapper());

        seeder.seedOnce();

        verify(repository, times(0)).save(any(DatabaseQueryConfig.class));
        verify(jdbc, times(0)).update(anyString(), any(), any());
    }
}
