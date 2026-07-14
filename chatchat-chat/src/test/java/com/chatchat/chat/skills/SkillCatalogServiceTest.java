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

    private SkillDefinition draftWithEnvironment(String environment) {
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
            Map.of("enabled", true, "runtimeEnvironment", environment),
            null,
            null,
            List.of(),
            SkillCatalogService.MARKET_STATUS_DRAFT,
            false
        );
    }
}
