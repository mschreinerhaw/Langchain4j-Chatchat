package com.chatchat.chat.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCatalogServiceTest {

    @Test
    void persistsAndReadsBackRuntimeEnvironment() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class));

        SkillDefinition saved = service.upsert(draftWithEnvironment("dev"));

        ArgumentCaptor<SkillConfigEntity> entityCaptor = ArgumentCaptor.forClass(SkillConfigEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getWorkflowConfigJson())
            .contains("\"runtimeEnvironment\":\"DEV\"");
        assertThat(saved.workflowConfig()).containsEntry("runtimeEnvironment", "DEV");
    }

    @Test
    void persistsStructuredFinancialDataForceFlag() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class));

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of(
            "enabled", true,
            "force_structured_financial_data", true
        )));

        ArgumentCaptor<SkillConfigEntity> entityCaptor = ArgumentCaptor.forClass(SkillConfigEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getWorkflowConfigJson())
            .contains("\"forceStructuredFinancialData\":true");
        assertThat(saved.workflowConfig()).containsEntry("forceStructuredFinancialData", true);
    }

    @Test
    void rejectsUnknownRuntimeEnvironment() {
        SkillCatalogService service = new SkillCatalogService(
            mock(SkillConfigRepository.class),
            mock(SkillConfigVersionRepository.class),
            new ObjectMapper(),
            mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.upsert(draftWithEnvironment("sandbox")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DEV, TEST, UAT, PROD");
    }

    @Test
    void normalizesAndPersistsAgentBudgetCeilings() {
        SkillConfigRepository repository = mock(SkillConfigRepository.class);
        SkillConfigVersionRepository versionRepository = mock(SkillConfigVersionRepository.class);
        when(repository.findById("db_ops_assistant")).thenReturn(Optional.empty());
        when(repository.save(any(SkillConfigEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(versionRepository.save(any(SkillConfigVersionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        SkillCatalogService service = new SkillCatalogService(
            repository, versionRepository, new ObjectMapper(), mock(JdbcTemplate.class));

        SkillDefinition saved = service.upsert(draftWithWorkflow(Map.of(
            "enabled", true,
            "executionStrategy", Map.of(
                "max_steps", 6,
                "cost_budget", 12.5,
                "latency_budget_ms", 180000
            )
        )));

        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) saved.workflowConfig().get("executionStrategy");
        assertThat(strategy)
            .containsEntry("maxSteps", 6)
            .containsEntry("costBudget", 12.5)
            .containsEntry("latencyBudgetMs", 180000)
            .doesNotContainKeys("max_steps", "cost_budget", "latency_budget_ms");
    }

    private SkillDefinition draftWithEnvironment(String environment) {
        return draftWithWorkflow(Map.of("enabled", true, "runtimeEnvironment", environment));
    }

    private SkillDefinition draftWithWorkflow(Map<String, Object> workflowConfig) {
        return new SkillDefinition(
            "db_ops_assistant",
            "数据库运维助手",
            null,
            List.of(),
            List.of(),
            "agent_chat",
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            workflowConfig,
            null,
            null,
            List.of(),
            SkillCatalogService.MARKET_STATUS_DRAFT,
            false
        );
    }
}
