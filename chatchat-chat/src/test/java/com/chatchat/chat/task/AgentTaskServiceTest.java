package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

    @Test
    void startsMultipleWorkersForOneTenantUpToConfiguredLimit() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.setMaxConcurrentTasksPerTenant(2);
        when(eventBus.pendingQuestionCount("tenant-1")).thenReturn(3);
        AgentTaskService service = new AgentTaskService(
            eventBus,
            mock(AgentEventStore.class),
            mock(AgentTaskLatestRepository.class),
            mock(InteractionOrchestrationService.class),
            new ObjectMapper(),
            properties,
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            executor
        );
        Method startWorkers = AgentTaskService.class.getDeclaredMethod("startWorkers", String.class);
        startWorkers.setAccessible(true);

        startWorkers.invoke(service, "tenant-1");

        verify(executor, times(2)).submit(any(Runnable.class));
    }

    @Test
    void taskRuntimeAttributesContainConfiguredExecutionTimeout() throws Exception {
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.setExecutionTimeoutMs(123_456);
        AgentTaskService service = new AgentTaskService(
            mock(AgentEventBus.class),
            mock(AgentEventStore.class),
            mock(AgentTaskLatestRepository.class),
            mock(InteractionOrchestrationService.class),
            new ObjectMapper(),
            properties,
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );
        Method attach = AgentTaskService.class.getDeclaredMethod(
            "attachCancellationCheck", InteractionRequest.class, String.class);
        attach.setAccessible(true);
        InteractionRequest request = new InteractionRequest();

        attach.invoke(service, request, "task-timeout");

        assertThat(request.getToolInput())
            .containsEntry("__agentTaskId", "task-timeout")
            .containsEntry("__agentRunId", "task-timeout")
            .containsEntry("__agentTimeoutMs", 123_456L);
    }

    @Test
    void confirmationIsPersistedWithoutHoldingTheTenantWorker() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        InteractionOrchestrationService orchestration = mock(InteractionOrchestrationService.class);
        TaskConfirmRepository confirmationRepository = mock(TaskConfirmRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentTaskLatestEntity latest = new AgentTaskLatestEntity();
        latest.setTaskId("task-confirm");
        latest.setTenantId("tenant-1");
        latest.setUserId("user-1");
        latest.setAgentId("sql-agent");
        latest.setSessionId("session-confirm");
        latest.setStatus("PENDING");
        latest.setCreateTime(Instant.now());
        latest.setUpdateTime(Instant.now());
        when(latestRepository.findById("task-confirm")).thenReturn(Optional.of(latest));
        when(confirmationRepository.save(any(TaskConfirmEntity.class))).thenAnswer(invocation -> {
            TaskConfirmEntity confirmation = invocation.getArgument(0);
            confirmation.onCreate();
            return confirmation;
        });
        when(orchestration.chat(any())).thenReturn(InteractionResponse.builder()
            .mode("agent_chat")
            .metadata(Map.of("confirmationRequired", true))
            .build());
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            orchestration,
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            confirmationRepository,
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );
        AgentTaskSubmitRequest submit = new AgentTaskSubmitRequest();
        submit.setTenantId("tenant-1");
        submit.setUserId("user-1");
        submit.setAgentId("sql-agent");
        submit.setSessionId("session-confirm");
        submit.setQuery("需要执行确认");
        AgentEvent question = AgentEvent.builder()
            .taskId("task-confirm")
            .tenantId("tenant-1")
            .userId("user-1")
            .agentId("sql-agent")
            .sessionId("session-confirm")
            .type("QUESTION")
            .status("PENDING")
            .payload(objectMapper.writeValueAsString(new AgentTaskPayload(submit)))
            .build();
        Method handleQuestion = AgentTaskService.class.getDeclaredMethod("handleQuestion", AgentEvent.class);
        handleQuestion.setAccessible(true);

        handleQuestion.invoke(service, question);

        assertThat(latest.getStatus()).isEqualTo("WAIT_CONFIRMATION");
        verify(eventBus).publishResult(any(AgentEvent.class));
        verify(eventBus, never()).pollConfirmation(anyLong(), any(TimeUnit.class));
    }

    @Test
    void confirmPersistsAndPublishesCommandEventWithoutInlineResume() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        TaskConfirmRepository confirmationRepository = mock(TaskConfirmRepository.class);
        AgentTaskLatestEntity latest = waitingTask("task-confirm-command");
        TaskConfirmEntity confirmation = waitingConfirmation("task-confirm-command");
        when(latestRepository.findById(latest.getTaskId())).thenReturn(Optional.of(latest));
        when(confirmationRepository.findTopByTaskIdOrderByCreatedAtDesc(latest.getTaskId()))
            .thenReturn(Optional.of(confirmation));
        AgentTaskService service = taskService(
            eventBus, eventStore, latestRepository, confirmationRepository, new ObjectMapper().findAndRegisterModules());
        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setUserId("user-1");
        request.setToolInput(Map.of("mcpConfirmation", Map.of("approved", true)));

        AgentTaskResponse response = service.confirm("tenant-1", latest.getTaskId(), request);

        ArgumentCaptor<AgentEvent> eventCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventStore).save(eventCaptor.capture());
        AgentEvent command = eventCaptor.getValue();
        assertThat(command.getType()).isEqualTo("CONFIRMATION");
        assertThat(command.getStatus()).isEqualTo("PENDING");
        assertThat(command.getTaskId()).isEqualTo(latest.getTaskId());
        verify(eventBus).publishConfirmation(command);
        verify(eventBus, never()).publish(any(AgentEvent.class));
        assertThat(response.status()).isEqualTo("WAIT_CONFIRMATION");
    }

    @Test
    void confirmationConsumerRestoresPendingToolAndRequeuesQuestionEvent() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        TaskConfirmRepository confirmationRepository = mock(TaskConfirmRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentTaskLatestEntity latest = waitingTask("task-confirm-consume");
        AgentTaskSubmitRequest original = new AgentTaskSubmitRequest();
        original.setTenantId("tenant-1");
        original.setUserId("user-1");
        original.setAgentId("sql-agent");
        original.setSessionId("session-1");
        original.setQuery("execute pending tool");
        latest.setRequestPayloadJson(objectMapper.writeValueAsString(new AgentTaskPayload(original)));
        TaskConfirmEntity confirmation = waitingConfirmation(latest.getTaskId());
        when(latestRepository.findById(latest.getTaskId())).thenReturn(Optional.of(latest));
        when(confirmationRepository.findTopByTaskIdOrderByCreatedAtDesc(latest.getTaskId()))
            .thenReturn(Optional.of(confirmation));
        AgentEvent pendingTool = AgentEvent.builder()
            .taskId(latest.getTaskId())
            .tenantId("tenant-1")
            .sessionId("session-1")
            .type("TOOL_CALL")
            .payload("""
                {"toolName":"database_query","input":{"sql":"select 1"},
                 "runtime":{"outcome":"confirmation_required",
                 "executionPlan":{"toolName":"database_query"},
                 "confirmation":{"token":"token-1"}}}
                """)
            .build();
        when(eventStore.listByTask("tenant-1", "session-1", latest.getTaskId(), 50))
            .thenReturn(List.of(pendingTool));
        AgentTaskService service = taskService(
            eventBus, eventStore, latestRepository, confirmationRepository, objectMapper);
        AgentTaskSubmitRequest approval = new AgentTaskSubmitRequest();
        approval.setUserId("user-1");
        approval.setToolInput(Map.of(
            "mcpConfirmation", Map.of("token", "token-1", "approved", true)));
        AgentEvent command = AgentEvent.builder()
            .taskId(latest.getTaskId())
            .tenantId("tenant-1")
            .userId("user-1")
            .agentId("sql-agent")
            .sessionId("session-1")
            .type("CONFIRMATION")
            .status("PENDING")
            .payload(objectMapper.writeValueAsString(approval))
            .build();

        service.consumeConfirmationEvent(command);

        assertThat(latest.getStatus()).isEqualTo("PENDING");
        assertThat(confirmation.getStatus()).isEqualTo("CONFIRMED");
        ArgumentCaptor<AgentEvent> questionCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventBus).publish(questionCaptor.capture());
        assertThat(questionCaptor.getValue().getType()).isEqualTo("QUESTION");
        AgentTaskPayload resumedPayload =
            objectMapper.readValue(questionCaptor.getValue().getPayload(), AgentTaskPayload.class);
        assertThat(resumedPayload.toSubmitRequest().getToolInput())
            .containsKeys("mcpConfirmation", "mcpPendingToolExecution");
    }

    @Test
    void recoveryRepublishesPersistedUnconsumedConfirmationCommand() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        TaskConfirmRepository confirmationRepository = mock(TaskConfirmRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AgentTaskLatestEntity latest = waitingTask("task-confirm-recovery");
        TaskConfirmEntity confirmation = waitingConfirmation(latest.getTaskId());
        AgentEvent command = AgentEvent.builder()
            .taskId(latest.getTaskId())
            .tenantId(latest.getTenantId())
            .userId(latest.getUserId())
            .agentId(latest.getAgentId())
            .sessionId(latest.getSessionId())
            .type("CONFIRMATION")
            .status("PENDING")
            .payload(objectMapper.writeValueAsString(new AgentTaskSubmitRequest()))
            .build();
        when(latestRepository.findByStatusInOrderByCreateTimeAsc(any())).thenReturn(List.of(latest));
        when(confirmationRepository.findTopByTaskIdOrderByCreatedAtDesc(latest.getTaskId()))
            .thenReturn(Optional.of(confirmation));
        when(eventStore.listByTask(
            latest.getTenantId(), latest.getSessionId(), latest.getTaskId(), Integer.MAX_VALUE))
            .thenReturn(List.of(command));
        AgentTaskService service = taskService(
            eventBus, eventStore, latestRepository, confirmationRepository, objectMapper);

        int recovered = service.recoverActiveTasks();

        assertThat(recovered).isZero();
        verify(eventBus).publishConfirmation(command);
        verify(eventBus, never()).publish(any(AgentEvent.class));
    }

    @Test
    void recoveryMarksTaskWithMissingQuestionEventFailedWithoutAbortingBatch() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-missing-question");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setAgentId("general");
        task.setSessionId("session-1");
        task.setStatus("WAIT_MODEL");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        when(latestRepository.findByStatusInOrderByCreateTimeAsc(any())).thenReturn(List.of(task));
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventStore.findFirstByTaskAndType(
            task.getTenantId(), task.getSessionId(), task.getTaskId(), "QUESTION"))
            .thenReturn(Optional.empty());
        when(eventStore.nextSequence(task.getTenantId(), task.getSessionId(), task.getTaskId())).thenReturn(1L);
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            new ObjectMapper(),
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );

        int recovered = service.recoverActiveTasks();

        assertThat(recovered).isZero();
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getErrorMessage()).contains("Question payload not found", task.getTaskId());
        ArgumentCaptor<AgentEvent> eventCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventStore).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(eventCaptor.getValue().getPayload()).contains("TASK_RECOVERY_PAYLOAD_INVALID");
    }

    @Test
    void recoveryRebuildsLegacyQuestionEventFromRelationalSnapshot() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-legacy-recovery");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setAgentId("general");
        task.setSessionId("session-1");
        task.setQuestion("发送今日市场分析邮件");
        task.setStatus("WAIT_MODEL");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        when(latestRepository.findByStatusInOrderByCreateTimeAsc(any())).thenReturn(List.of(task));
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventStore.findFirstByTaskAndType(
            task.getTenantId(), task.getSessionId(), task.getTaskId(), "QUESTION"))
            .thenReturn(Optional.empty());
        when(eventStore.nextSequence(task.getTenantId(), task.getSessionId(), task.getTaskId())).thenReturn(1L);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );

        int recovered = service.recoverActiveTasks();

        assertThat(recovered).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getRequestPayloadJson()).isNotBlank();
        ArgumentCaptor<AgentEvent> published = ArgumentCaptor.forClass(AgentEvent.class);
        verify(eventBus).publish(published.capture());
        AgentTaskPayload payload = objectMapper.readValue(published.getValue().getPayload(), AgentTaskPayload.class);
        assertThat(payload.getRequest().getQuery()).isEqualTo(task.getQuestion());
        assertThat(payload.getRequest().getSkillId()).isEqualTo(task.getAgentId());
        assertThat(published.getValue().getType()).isEqualTo("QUESTION");
    }

    @Test
    void finalAnswerReadsCompleteModelAnswerInsteadOfLatestSummary() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-complete-answer");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setAgentId("general");
        task.setSessionId("session-1");
        task.setStatus("SUCCESS");
        task.setAnswerSummary("只有五百字的摘要");
        String fullAnswer = "# 今日市场热点分析\n\n" + "完整模型回答。".repeat(800);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentEvent complete = AgentEvent.builder()
            .taskId(task.getTaskId())
            .tenantId(task.getTenantId())
            .userId(task.getUserId())
            .agentId(task.getAgentId())
            .sessionId(task.getSessionId())
            .type("COMPLETE")
            .status("SUCCESS")
            .payload(objectMapper.writeValueAsString(
                java.util.Map.of("uiResponse", java.util.Map.of("answer", fullAnswer))))
            .build();
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), Integer.MAX_VALUE))
            .thenReturn(List.of(complete));
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );

        Optional<String> answer = service.finalAnswer(task.getTenantId(), task.getTaskId());

        assertThat(answer).contains(fullAnswer);
        assertThat(answer.orElseThrow()).hasSize(fullAnswer.length());
    }

    @Test
    void finalNotificationUsesPersistedFullAnswerAndReferencesWhenEventsAreUnavailable() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository latestRepository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-persisted-notification");
        task.setTenantId("tenant-1");
        task.setUserId("user-1");
        task.setAgentId("general");
        task.setSessionId("session-1");
        task.setStatus("SUCCESS");
        String fullAnswer = "# 今日市场热点分析\n\n" + "完整模型回答。".repeat(800);
        ObjectMapper objectMapper = new ObjectMapper();
        task.setAnswerSummary(fullAnswer);
        task.setFinalNotificationJson(objectMapper.writeValueAsString(Map.of(
            "answer", fullAnswer,
            "references", List.of(Map.of(
                "title", "交易所公告",
                "url", "https://example.com/notice/1"
            ))
        )));
        when(latestRepository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), Integer.MAX_VALUE))
            .thenReturn(List.of());
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );

        AgentTaskService.AgentNotificationContent content = service
            .finalNotificationContent(task.getTenantId(), task.getTaskId())
            .orElseThrow();

        assertThat(content.answer()).isEqualTo(fullAnswer);
        assertThat(content.references()).hasSize(1);
        assertThat(content.references().get(0))
            .containsEntry("title", "交易所公告")
            .containsEntry("url", "https://example.com/notice/1");
    }

    @Test
    void cleanDisplayAnswerPreservesSqlCodeFence() {
        String answer = """
            ## JDBC SQL 案例

            ```sql
            CREATE TABLE MyUserTable (
              id BIGINT,
              name STRING
            ) WITH (
              'connector' = 'jdbc',
              'url' = 'jdbc:mysql://localhost:3306/mydatabase'
            );
            ```

            来源：[doc://jdbc#chunk=2]
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("```sql")
            .contains("CREATE TABLE MyUserTable")
            .contains("'connector' = 'jdbc'")
            .contains("来源：[doc://jdbc#chunk=2]");
    }

    @Test
    void cleanDisplayAnswerStillRemovesJsonProtocolFence() {
        String answer = """
            ## 结果

            ```json
            {"uiResponse":{"answer":"internal"}}
            ```

            可展示内容
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("可展示内容")
            .doesNotContain("uiResponse")
            .doesNotContain("```json");
    }

    @Test
    void citationsRecoverReadableLinksFromSuccessfulWebSearchTrace() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTaskService service = new AgentTaskService(
            mock(AgentEventBus.class),
            mock(AgentEventStore.class),
            mock(AgentTaskLatestRepository.class),
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );
        InteractionResponse response = InteractionResponse.builder()
            .toolTraces(List.of(InteractionToolTrace.builder()
                .toolName("mcp_chatchat_mcp_server_web_search")
                .success(true)
                .output("""
                    {
                      "reference_urls": ["https://example.com/news/2", "https://example.com/news/1"],
                      "results": [
                        {
                          "title": "第一条资讯",
                          "url": "https://example.com/news/1",
                          "sourceName": "示例财经",
                          "publishTime": "2026-07-20T10:00:00+08:00",
                          "snippet": "第一条摘要"
                        },
                        {
                          "title": "第二条资讯",
                          "sourceUrl": "https://example.com/news/2",
                          "evidence": {"sourceName": "交易所", "publishTime": "2026-07-20"}
                        }
                      ]
                    }
                    """)
                .build()))
            .build();

        List<Map<String, Object>> citations = service.citations(response, Map.of());

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0))
            .containsEntry("rank", 1)
            .containsEntry("title", "第二条资讯")
            .containsEntry("publisher", "交易所")
            .containsEntry("url", "https://example.com/news/2");
        assertThat(citations.get(1))
            .containsEntry("rank", 2)
            .containsEntry("publisher", "示例财经")
            .containsEntry("publishDate", "2026-07-20T10:00:00+08:00")
            .containsEntry("url", "https://example.com/news/1");
    }

    @Test
    void citationsRecoverLinksFromNestedMcpStructuredContentAndEvidenceChunks() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTaskService service = new AgentTaskService(
            mock(AgentEventBus.class),
            mock(AgentEventStore.class),
            mock(AgentTaskLatestRepository.class),
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            mock(TaskConfirmRepository.class),
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );
        InteractionResponse response = InteractionResponse.builder()
            .toolTraces(List.of(InteractionToolTrace.builder()
                .toolName("mcp_chatchat_mcp_server_web_search")
                .success(true)
                .output("""
                    {
                      "structuredContent": {
                        "data": {
                          "reference_urls": ["https://www.sse.com.cn/market/view/", "https://www.cls.cn/detail/1"],
                          "evidence_chunks": [
                            {
                              "title": "上交所市场总貌",
                              "citation": {"url": "https://www.sse.com.cn/market/view/", "publisher": "上交所"},
                              "snippet": "市场统计"
                            },
                            {
                              "title": "市场新闻",
                              "url": "https://www.cls.cn/detail/1",
                              "sourceName": "财联社"
                            }
                          ]
                        }
                      }
                    }
                    """)
                .build()))
            .build();

        List<Map<String, Object>> citations = service.citations(response, Map.of());

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0))
            .containsEntry("rank", 1)
            .containsEntry("title", "上交所市场总貌")
            .containsEntry("publisher", "上交所")
            .containsEntry("url", "https://www.sse.com.cn/market/view/");
        assertThat(citations.get(1))
            .containsEntry("rank", 2)
            .containsEntry("publisher", "财联社")
            .containsEntry("url", "https://www.cls.cn/detail/1");
    }

    private AgentTaskService taskService(AgentEventBus eventBus,
                                         AgentEventStore eventStore,
                                         AgentTaskLatestRepository latestRepository,
                                         TaskConfirmRepository confirmationRepository,
                                         ObjectMapper objectMapper) {
        return new AgentTaskService(
            eventBus,
            eventStore,
            latestRepository,
            mock(InteractionOrchestrationService.class),
            objectMapper,
            new AgentTaskProperties(),
            mock(ToolRuntimeService.class),
            mock(AgentRuntime.class),
            mock(AgentTaskCancellationRegistry.class),
            mock(AgentLearningService.class),
            confirmationRepository,
            mock(InterpretationPlanStore.class),
            mock(ThreadPoolTaskExecutor.class)
        );
    }

    private AgentTaskLatestEntity waitingTask(String taskId) {
        AgentTaskLatestEntity latest = new AgentTaskLatestEntity();
        latest.setTaskId(taskId);
        latest.setTenantId("tenant-1");
        latest.setUserId("user-1");
        latest.setAgentId("sql-agent");
        latest.setSessionId("session-1");
        latest.setStatus("WAIT_CONFIRMATION");
        latest.setQuestion("execute pending tool");
        latest.setCreateTime(Instant.now());
        latest.setUpdateTime(Instant.now());
        return latest;
    }

    private TaskConfirmEntity waitingConfirmation(String taskId) {
        TaskConfirmEntity confirmation = new TaskConfirmEntity();
        confirmation.setId("confirm-" + taskId);
        confirmation.setTaskId(taskId);
        confirmation.setStatus("WAITING_CONFIRM");
        confirmation.setExpiredAt(Instant.now().plusSeconds(600));
        return confirmation;
    }
}
