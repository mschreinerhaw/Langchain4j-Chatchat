package com.chatchat.chat.skills.domain;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainSkillImportTaskServiceTest {
    @Test
    void returnsQueuedTaskBeforeSlowCompilationRuns() {
        DomainSkillImportTaskRepository repository = mock(DomainSkillImportTaskRepository.class);
        DomainSkillService skills = mock(DomainSkillService.class);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        TaskExecutor executor = queued::set;
        AtomicReference<DomainSkillImportTaskEntity> persisted = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            DomainSkillImportTaskEntity task = invocation.getArgument(0);
            if (task.getId() == null) task.create();
            persisted.set(task);
            return task;
        });
        when(repository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        DomainSkillEntity imported = new DomainSkillEntity();
        imported.setId("skill-1");
        when(skills.importFile(any(), any(), any(), any(), any(), any())).thenReturn(imported);
        DomainSkillImportTaskService service = new DomainSkillImportTaskService(repository, skills, executor);

        DomainSkillImportTaskService.ImportTaskView submitted = service.enqueueFile(
            "tenant-a", "admin", "# Skill".getBytes(StandardCharsets.UTF_8), "SKILL.md", "", "Research");

        assertThat(submitted.status()).isEqualTo("QUEUED");
        assertThat(queued.get()).isNotNull();
        verify(skills, org.mockito.Mockito.never()).importFile(any(), any(), any(), any(), any(), any());

        queued.get().run();

        assertThat(persisted.get().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(persisted.get().getSkillId()).isEqualTo("skill-1");
        verify(skills).importFile(eq("tenant-a"), eq("admin"),
            argThat(bytes -> java.util.Arrays.equals(bytes, "# Skill".getBytes(StandardCharsets.UTF_8))),
            eq("SKILL.md"), eq(""), eq("Research"));
    }
}
