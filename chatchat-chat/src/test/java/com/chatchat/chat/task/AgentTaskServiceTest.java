package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

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
}
