package com.chatchat.api.runtime.coding.knowledge;

import com.chatchat.api.runtime.coding.CodingInvocationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExperienceProvenanceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KnowledgeDisclosureGuard guard = spy(new KnowledgeDisclosureGuard(jdbc, new ObjectMapper(),
        (CodingInvocationContext) null, null, null, null));

    @Test void usesPersistedTenantScopedProducerEvenWhenSummaryContainsNoCitation() {
        var record = Map.<String, Object>of("producer_task_id", "task", "execution_attempt_id", "run", "answer_summary", "truncated");
        when(jdbc.queryForList(startsWith("select e.*"), eq("tenant"), eq("id"), eq("task"))).thenReturn(List.of(record));
        doReturn(true).when(guard).checkRun("tenant", "owner", "run");
        guard.checkExperience("tenant", "owner", "id", "task");
        verify(guard).checkRun("tenant", "owner", "run");
    }

    @Test void missingProducerCannotExposeRetainedContent() {
        when(jdbc.queryForList(startsWith("select e.*"), eq("tenant"), eq("id"), eq("task")))
            .thenReturn(List.of(Map.of("answer_summary", "private")));
        assertThatThrownBy(() -> guard.checkExperience("tenant", "owner", "id", "task"))
            .isInstanceOf(SecurityException.class);
    }

    @Test void aggregateValidationIncludesExperiencesBeyondThePageAndKeepsTheBound() {
        when(jdbc.queryForList(startsWith("select experience_id"), eq("tenant")))
            .thenReturn(List.of(Map.of("experience_id", "outside", "task_id", "task-outside")));
        doThrow(new SecurityException("revoked")).when(guard).checkExperience("tenant", "owner", "outside", "task-outside");
        assertThatThrownBy(() -> guard.checkExperienceAggregates("tenant", "owner", Set.of("visible")))
            .isInstanceOf(SecurityException.class);
        when(jdbc.queryForList(startsWith("select experience_id"), eq("tenant")))
            .thenReturn(java.util.Collections.nCopies(513, Map.of("experience_id", "id", "task_id", "task")));
        assertThatThrownBy(() -> guard.checkExperienceAggregates("tenant", "owner", Set.of()))
            .isInstanceOf(SecurityException.class).hasMessageContaining("budget");
    }
}
