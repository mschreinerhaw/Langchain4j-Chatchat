package com.chatchat.chat.task;

import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.integration.mcp.service.McpNotificationClient;
import com.chatchat.integration.mcp.service.McpTradingCalendarClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScheduledTaskServiceTest {

    @Test
    void terminalTaskEventImmediatelyReconcilesScheduledRunAndSchedule() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        ScheduledTaskRunRepository runRepository = mock(ScheduledTaskRunRepository.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);

        AgentTaskLatestEntity latest = new AgentTaskLatestEntity();
        latest.setTaskId("agent-task-1");
        latest.setStatus("KILLED");
        latest.setErrorMessage("Task killed by runtime request");

        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setRunId("run-1");
        run.setScheduledTaskId("schedule-1");
        run.setTaskId("agent-task-1");
        run.setStatus("RUNNING");
        run.setManualRun(false);
        run.setFireTime(Instant.now().minusSeconds(10));

        ScheduledTaskEntity schedule = new ScheduledTaskEntity();
        schedule.setTaskId("schedule-1");
        schedule.setTenantId("tenant-1");
        schedule.setStatus("RUNNING");
        schedule.setTriggerType("CRON");
        schedule.setCronExpr("0 0 8 * * ?");
        schedule.setZoneId("Asia/Shanghai");
        schedule.setLastTaskId("agent-task-1");
        schedule.setMaxRetries(0);
        schedule.setRetryCount(0);

        when(latestRepository.findById("agent-task-1")).thenReturn(Optional.of(latest));
        when(runRepository.findFirstByTaskIdOrderByFireTimeDesc("agent-task-1")).thenReturn(Optional.of(run));
        when(runRepository.claimCompletion("run-1")).thenReturn(1);
        when(repository.findFirstByLastTaskId("agent-task-1")).thenReturn(Optional.of(schedule));
        when(repository.findById("schedule-1")).thenReturn(Optional.of(schedule));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(ScheduledTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentScheduledTaskService service = new AgentScheduledTaskService(
            repository,
            runRepository,
            latestRepository,
            mock(AgentTaskService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(McpTradingCalendarClient.class),
            mock(McpNotificationClient.class),
            mock(TenantNotificationRecipientService.class),
            mock(EnterpriseAdminService.class),
            skillCatalogService,
            mock(NotificationContentFormatter.class),
            new AgentScheduleWindowPolicy()
        );

        service.reconcileTerminalTask("agent-task-1");

        assertThat(run.getStatus()).isEqualTo("CANCELLED");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(schedule.getLastTaskStatus()).isEqualTo("KILLED");
        assertThat(schedule.getStatus()).isEqualTo("ACTIVE");
        assertThat(schedule.getNextFireTime()).isNotNull();
        verify(runRepository).claimCompletion("run-1");
        verify(repository).save(schedule);
    }

    @Test
    void terminalTaskEventRecoversPreviouslyClaimedCompletionWithoutReclaimingRow() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        ScheduledTaskRunRepository runRepository = mock(ScheduledTaskRunRepository.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);

        AgentTaskLatestEntity latest = new AgentTaskLatestEntity();
        latest.setTaskId("agent-task-2");
        latest.setStatus("SUCCESS");
        latest.setAnswerSummary("done");

        ScheduledTaskRunEntity run = new ScheduledTaskRunEntity();
        run.setRunId("run-2");
        run.setScheduledTaskId("schedule-2");
        run.setTaskId("agent-task-2");
        run.setStatus("COMPLETING");
        run.setManualRun(true);
        run.setFireTime(Instant.now().minusSeconds(5));

        when(latestRepository.findById("agent-task-2")).thenReturn(Optional.of(latest));
        when(runRepository.findFirstByTaskIdOrderByFireTimeDesc("agent-task-2")).thenReturn(Optional.of(run));
        when(repository.findById("schedule-2")).thenReturn(Optional.empty());
        when(runRepository.save(any(ScheduledTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentScheduledTaskService service = new AgentScheduledTaskService(
            repository,
            runRepository,
            latestRepository,
            mock(AgentTaskService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(McpTradingCalendarClient.class),
            mock(McpNotificationClient.class),
            mock(TenantNotificationRecipientService.class),
            mock(EnterpriseAdminService.class),
            mock(SkillCatalogService.class),
            mock(NotificationContentFormatter.class),
            new AgentScheduleWindowPolicy()
        );

        service.reconcileTerminalTask("agent-task-2");

        assertThat(run.getStatus()).isEqualTo("SUCCESS");
        assertThat(run.getAnswerSummary()).isEqualTo("done");
        verify(runRepository, never()).claimCompletion("run-2");
        verify(runRepository).save(run);
    }

    @Test
    void updateKeepsScheduleIdentityAndPersistsEditedDefinition() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        ScheduledTaskRunRepository runRepository = mock(ScheduledTaskRunRepository.class);
        SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);
        McpNotificationClient notificationClient = mock(McpNotificationClient.class);
        TenantNotificationRecipientService recipientService = mock(TenantNotificationRecipientService.class);
        ScheduledTaskEntity existing = new ScheduledTaskEntity();
        existing.setTaskId("schedule-1");
        existing.setTenantId("tenant-1");
        existing.setUserId("user-1");
        existing.setAgentId("agent-1");
        existing.setName("旧任务");
        existing.setQuestion("旧问题");
        existing.setTriggerType("CRON");
        existing.setCronExpr("0 0 8 * * ?");
        existing.setPayloadJson("{}");
        existing.setStatus("ACTIVE");
        existing.setNotifyEnabled(false);
        existing.setTradingDayOnly(false);
        existing.setScheduleWindowEnabled(false);
        existing.setZoneId("Asia/Shanghai");
        existing.setNextFireTime(Instant.now().plusSeconds(3600));
        when(repository.findById("schedule-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(ScheduledTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.existsByScheduledTaskIdAndStatus("schedule-1", "RUNNING")).thenReturn(false);
        when(skillCatalogService.isPublished("agent-1")).thenReturn(true);
        when(notificationClient.requireEnabled("email-channel")).thenReturn(
            new McpNotificationClient.NotificationChannelOption(
                "email-channel", "EMAIL", "send_email", "邮件", "", "HTTP", true
            )
        );
        when(recipientService.recipients("tenant-1", "EMAIL"))
            .thenReturn(List.of("default@example.com", "selected@example.com"));

        AgentScheduledTaskService service = new AgentScheduledTaskService(
            repository,
            runRepository,
            mock(AgentTaskLatestRepository.class),
            mock(AgentTaskService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(McpTradingCalendarClient.class),
            notificationClient,
            recipientService,
            mock(EnterpriseAdminService.class),
            skillCatalogService,
            mock(NotificationContentFormatter.class),
            new AgentScheduleWindowPolicy()
        );
        AgentTaskSubmitRequest payload = new AgentTaskSubmitRequest();
        payload.setTenantId("tenant-1");
        payload.setUserId("admin");
        payload.setAgentId("agent-1");
        payload.setSkillId("agent-1");
        payload.setQuery("更新后的问题");
        ScheduledAgentTaskRequest request = new ScheduledAgentTaskRequest();
        request.setTenantId("tenant-1");
        request.setUserId("admin");
        request.setAgentId("agent-1");
        request.setName("更新后的任务");
        request.setQuestion("更新后的问题");
        request.setTriggerType("CRON");
        request.setCron("0 30 9 * * ?");
        request.setEnabled(true);
        request.setNotifyEnabled(true);
        request.setNotificationChannelId("email-channel");
        request.setNotificationRecipientMode("SPECIFIC");
        request.setNotificationReceiver("selected@example.com");
        request.setTradingDayOnly(true);
        request.setScheduleWindowEnabled(true);
        request.setScheduleWindowStart("09:00");
        request.setScheduleWindowEnd("12:00");
        request.setZoneId("Asia/Shanghai");
        request.setPayload(payload);

        ScheduledTaskResponse response = service.update("tenant-1", "schedule-1", request);

        ArgumentCaptor<ScheduledTaskEntity> saved = ArgumentCaptor.forClass(ScheduledTaskEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTaskId()).isEqualTo("schedule-1");
        assertThat(saved.getValue().getUserId()).isEqualTo("user-1");
        assertThat(saved.getValue().getPayloadJson()).contains("\"userId\":\"user-1\"");
        assertThat(saved.getValue().getName()).isEqualTo("更新后的任务");
        assertThat(saved.getValue().getQuestion()).isEqualTo("更新后的问题");
        assertThat(saved.getValue().getCronExpr()).isEqualTo("0 30 9 * * ?");
        assertThat(saved.getValue().getNotificationRecipientMode()).isEqualTo("SPECIFIC");
        assertThat(saved.getValue().getNotificationReceiver()).isEqualTo("selected@example.com");
        assertThat(saved.getValue().getTradingDayOnly()).isTrue();
        assertThat(saved.getValue().getScheduleWindowStart()).isEqualTo("09:00");
        assertThat(saved.getValue().getScheduleWindowEnd()).isEqualTo("12:00");
        assertThat(saved.getValue().getNextFireTime()).isAfter(Instant.now());
        assertThat(response.scheduleId()).isEqualTo("schedule-1");
    }

    @Test
    void adoptsLegacyTenantTasksAndKeepsOriginalOwner() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        ScheduledTaskRunRepository runRepository = mock(ScheduledTaskRunRepository.class);
        ScheduledTaskEntity legacy = new ScheduledTaskEntity();
        legacy.setTaskId("legacy-schedule");
        legacy.setTenantId("default-user");
        legacy.setUserId("legacy-owner");
        when(repository.findByTenantIdIn(List.of("default", "default-user"))).thenReturn(List.of(legacy));

        AgentScheduledTaskService service = new AgentScheduledTaskService(
            repository,
            runRepository,
            mock(AgentTaskLatestRepository.class),
            mock(AgentTaskService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(McpTradingCalendarClient.class),
            mock(McpNotificationClient.class),
            mock(TenantNotificationRecipientService.class),
            mock(EnterpriseAdminService.class),
            mock(SkillCatalogService.class),
            mock(NotificationContentFormatter.class),
            new AgentScheduleWindowPolicy()
        );

        int adopted = service.adoptLegacyTenantTasks("tenant-1", List.of("default", "default-user"));

        assertThat(adopted).isEqualTo(1);
        assertThat(legacy.getTenantId()).isEqualTo("tenant-1");
        assertThat(legacy.getUserId()).isEqualTo("legacy-owner");
        verify(runRepository).updateOwner("legacy-schedule", "tenant-1", "legacy-owner");
        verify(repository).saveAll(List.of(legacy));
    }

    @Test
    void searchKeepsBlankStatusAsNoFilter() {
        ScheduledTaskRepository repository = mock(ScheduledTaskRepository.class);
        when(repository.search(anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), any(Pageable.class)))
            .thenReturn(Page.empty());
        AgentScheduledTaskService service = new AgentScheduledTaskService(
            repository,
            mock(ScheduledTaskRunRepository.class),
            mock(AgentTaskLatestRepository.class),
            mock(AgentTaskService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(McpTradingCalendarClient.class),
            mock(McpNotificationClient.class),
            mock(TenantNotificationRecipientService.class),
            mock(EnterpriseAdminService.class),
            mock(SkillCatalogService.class),
            mock(NotificationContentFormatter.class),
            new AgentScheduleWindowPolicy()
        );

        service.search("tenant-1", "", "", "", "", List.of(), 1, 10);

        verify(repository).search(
            eq("tenant-1"), eq(""), eq(""), eq(""), eq(""), eq(List.of("__no_matching_agent__")),
            any(Pageable.class)
        );
    }
}
