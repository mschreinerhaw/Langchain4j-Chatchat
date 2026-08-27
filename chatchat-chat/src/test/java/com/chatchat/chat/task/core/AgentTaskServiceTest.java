package com.chatchat.chat.task.core;

import com.chatchat.chat.task.learning.AgentLearningService;

import com.chatchat.chat.task.event.AgentEventStore;

import com.chatchat.chat.task.event.AgentEventBus;

import com.chatchat.chat.task.event.AgentEvent;

import com.chatchat.chat.task.queue.AgentTaskQueueCoordinator;

import com.chatchat.chat.task.core.TaskConfirmRepository;

import com.chatchat.chat.task.core.TaskConfirmEntity;

import com.chatchat.chat.task.core.AgentTaskSubmitRequest;

import com.chatchat.chat.task.core.AgentTaskService;

import com.chatchat.chat.task.core.AgentTaskResponse;

import com.chatchat.chat.task.core.AgentTaskProperties;

import com.chatchat.chat.task.core.AgentTaskPayload;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;

import com.chatchat.chat.task.core.AgentTaskLatestEntity;

import com.chatchat.chat.task.core.AgentTaskCancellationRegistry;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.InteractionOrchestrationService;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesClaimLedgerAndEvidenceManifestInPersistedResultPayload() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        Map<String, Object> claimLedger = Map.of(
            "contractVersion", "claim_ledger_v1", "status", "PASS", "coverage", 1.0);
        Map<String, Object> evidenceManifest = Map.of(
            "contractVersion", "evidence_manifest_v1", "evidenceCount", 1, "manifestHash", "hash-1");
        InteractionResponse response = InteractionResponse.builder()
            .answer("已基于证据完成回答。")
            .metadata(Map.of("agent", Map.of(
                "claimLedger", claimLedger,
                "evidenceManifest", evidenceManifest,
                "claimCoverage", 1.0,
                "claimCoverageStatus", "PASS",
                "answerClaimAuditPassed", true
            )))
            .build();
        Method compile = AgentTaskService.class.getDeclaredMethod("compileExecutionResult", InteractionResponse.class);
        compile.setAccessible(true);
        Object contract = compile.invoke(service, response);
        Method payloadMethod = contract.getClass().getDeclaredMethod("payload", InteractionResponse.class);
        payloadMethod.setAccessible(true);

        Map<String, Object> payload = (Map<String, Object>) payloadMethod.invoke(contract, response);

        assertThat(payload)
            .containsEntry("claimLedger", claimLedger)
            .containsEntry("evidenceManifest", evidenceManifest)
            .containsEntry("claimCoverage", 1.0)
            .containsEntry("claimCoverageStatus", "PASS");
        assertThat((Map<String, Object>) payload.get("metadata"))
            .containsKey("agent")
            .doesNotContainKeys("observations", "toolResultEvidence");
    }

    @Test
    void failedToolResultDoesNotOverrideLaterPartialTaskCompletion() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        AgentEvent failedTool = AgentEvent.builder()
            .type("TOOL_RESULT")
            .status("FAILED")
            .toolName("mcp_chatchat_mcp_server_api_template_execute")
            .build();
        AgentEvent partialResult = AgentEvent.builder()
            .type("RESULT")
            .status("PARTIAL_SUCCESS")
            .payload("{\"answer\":\"available evidence\"}")
            .build();
        Method findTerminal = AgentTaskService.class.getDeclaredMethod("findLatestTerminalEvent", List.class);
        findTerminal.setAccessible(true);

        AgentEvent terminal = (AgentEvent) findTerminal.invoke(service, List.of(failedTool, partialResult));

        assertThat(terminal).isSameAs(partialResult);
    }

    @Test
    void failedToolResultAloneIsNotATaskTerminalEvent() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        AgentEvent failedTool = AgentEvent.builder()
            .type("TOOL_RESULT")
            .status("FAILED")
            .build();
        Method findTerminal = AgentTaskService.class.getDeclaredMethod("findLatestTerminalEvent", List.class);
        findTerminal.setAccessible(true);

        assertThat(findTerminal.invoke(service, List.of(failedTool))).isNull();
    }

    @Test
    void failedToolWithCapturedEvidenceCompilesAsPartialSuccess() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        InteractionResponse response = InteractionResponse.builder()
            .answer("已返回可用结果，并明确说明其中一个指标查询失败。")
            .toolTraces(List.of(
                InteractionToolTrace.builder().toolName("api_asset_query").success(true).output("asset").build(),
                InteractionToolTrace.builder().toolName("api_template_execute").success(false)
                    .errorMessage("UNAVAILABLE: NameResolver returned no usable address").build()
            ))
            .metadata(Map.of("agent", Map.of("fatalExecutionBlocked", true)))
            .build();
        Method compile = AgentTaskService.class.getDeclaredMethod("compileExecutionResult", InteractionResponse.class);
        compile.setAccessible(true);

        Object contract = compile.invoke(service, response);
        Method status = contract.getClass().getDeclaredMethod("status");
        status.setAccessible(true);

        assertThat(status.invoke(contract)).isEqualTo("PARTIAL_SUCCESS");
    }

    @Test
    void snapshotsUserDefinedWorkflowAgainstTaskIdBeforeExecution() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        SkillDefinition skill = new SkillDefinition(
            "ops", "Ops", "", List.of(), List.of(), "agent_chat", null, "", "",
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            Map.of("mcpWorkflow", Map.of("steps", List.of(
                Map.of("id", "asset", "tool", "api_asset_query"),
                Map.of("id", "execute", "tool", "api_template_execute", "dependsOn", List.of("asset"))
            ))),
            null, null, List.of(), "published", false
        );
        when(catalog.resolve("ops")).thenReturn(skill);
        Field catalogField = AgentTaskService.class.getDeclaredField("skillCatalogService");
        catalogField.setAccessible(true);
        catalogField.set(service, catalog);
        Method snapshot = AgentTaskService.class.getDeclaredMethod(
            "snapshotUserDefinedWorkflow", AgentTaskSubmitRequest.class, String.class);
        snapshot.setAccessible(true);
        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setSkillId("ops");

        snapshot.invoke(service, request, "task-100");

        assertThat(request.getToolInput())
            .containsEntry("__taskWorkflowTaskId", "task-100")
            .containsEntry("__taskWorkflowSource", "user_defined_mcp_workflow")
            .containsKey("__taskWorkflowDefinition");
    }

    @Test
    void doesNotSnapshotRuntimeSettingsAsUserDefinedWorkflow() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        SkillCatalogService catalog = mock(SkillCatalogService.class);
        SkillDefinition skill = new SkillDefinition(
            "dynamic", "Dynamic", "", List.of(), List.of(), "agent_chat", null, "", "",
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            Map.of(
                "enabled", true,
                "runtimeEnvironment", "DEV",
                "resultHandlingPolicy", Map.of("mode", "SUMMARIZE_AVAILABLE")
            ),
            null, null, List.of(), "published", false
        );
        when(catalog.resolve("dynamic")).thenReturn(skill);
        Field catalogField = AgentTaskService.class.getDeclaredField("skillCatalogService");
        catalogField.setAccessible(true);
        catalogField.set(service, catalog);
        Method snapshot = AgentTaskService.class.getDeclaredMethod(
            "snapshotUserDefinedWorkflow", AgentTaskSubmitRequest.class, String.class);
        snapshot.setAccessible(true);
        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setSkillId("dynamic");

        snapshot.invoke(service, request, "task-dynamic");

        assertThat(request.getToolInput()).isNullOrEmpty();
    }

    @Test
    void repeatedIdempotentSubmissionReturnsSameTaskAndQueuesOnlyOnce() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository repository = mock(AgentTaskLatestRepository.class);
        AtomicReference<AgentTaskLatestEntity> saved = new AtomicReference<>();
        when(repository.findByTenantIdAndIdempotencyKey("tenant-1", "message-1001"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentTaskLatestEntity.class))).thenAnswer(invocation -> {
            AgentTaskLatestEntity entity = invocation.getArgument(0);
            saved.set(entity);
            when(repository.findById(entity.getTaskId())).thenReturn(Optional.of(entity));
            return entity;
        });
        AgentTaskService service = taskService(
            eventBus, eventStore, repository, mock(TaskConfirmRepository.class), new ObjectMapper());

        AgentTaskSubmitRequest first = idempotentRequest("message-1001", "analyse latest market evidence");
        first.setSessionId(null);
        AgentTaskResponse firstResponse = service.submit(first);
        AgentTaskLatestEntity persisted = saved.get();
        when(repository.findByTenantIdAndIdempotencyKey("tenant-1", "message-1001"))
            .thenReturn(Optional.of(persisted));
        AgentTaskSubmitRequest replay = idempotentRequest("message-1001", "analyse latest market evidence");
        replay.setSessionId(null);
        AgentTaskResponse replayResponse = service.submit(replay);

        assertThat(replayResponse.taskId()).isEqualTo(firstResponse.taskId());
        assertThat(firstResponse.taskId()).isEqualTo(persisted.getTaskId());
        verify(eventBus, times(1)).publish(any(AgentEvent.class));
        verify(eventStore, times(1)).save(any(AgentEvent.class));
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentRequest() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentTaskLatestRepository repository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity existing = taskFor(idempotentRequest("message-1002", "first query"));
        when(repository.findByTenantIdAndIdempotencyKey("tenant-1", "message-1002"))
            .thenReturn(Optional.of(existing));
        AgentTaskService service = taskService(
            eventBus, mock(AgentEventStore.class), repository,
            mock(TaskConfirmRepository.class), new ObjectMapper());

        assertThatThrownBy(() -> service.submit(idempotentRequest("message-1002", "different query")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already bound to a different");
        verify(eventBus, never()).publish(any(AgentEvent.class));
    }

    @Test
    void concurrentInsertConflictReturnsCommittedTaskWithoutDuplicateQueue() {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentTaskLatestRepository repository = mock(AgentTaskLatestRepository.class);
        AgentTaskSubmitRequest request = idempotentRequest("message-race", "race-safe query");
        AgentTaskLatestEntity winner = taskFor(request);
        when(repository.findByTenantIdAndIdempotencyKey("tenant-1", "message-race"))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.save(any(AgentTaskLatestEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        AgentTaskService service = taskService(
            eventBus, mock(AgentEventStore.class), repository,
            mock(TaskConfirmRepository.class), new ObjectMapper());

        AgentTaskResponse response = service.submit(request);

        assertThat(response.taskId()).isEqualTo(winner.getTaskId());
        verify(eventBus, never()).publish(any(AgentEvent.class));
    }

    @Test
    void databaseSubmissionIsStagedBeforeItBecomesDispatchable() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository repository = mock(AgentTaskLatestRepository.class);
        AgentTaskQueueCoordinator coordinator = mock(AgentTaskQueueCoordinator.class);
        AgentTaskProperties properties = new AgentTaskProperties();
        properties.setDatabaseQueueEnabled(true);
        List<String> lifecycle = new ArrayList<>();
        when(repository.findByTenantIdAndIdempotencyKey("tenant-1", "message-staged"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentTaskLatestEntity.class))).thenAnswer(invocation -> {
            AgentTaskLatestEntity entity = invocation.getArgument(0);
            lifecycle.add("save:" + entity.getStatus());
            return entity;
        });
        when(eventStore.save(any(AgentEvent.class))).thenAnswer(invocation -> {
            lifecycle.add("event:QUESTION");
            return "event-1";
        });
        when(coordinator.claimAvailable(any())).thenAnswer(invocation -> {
            lifecycle.add("dispatch");
            throw new IllegalStateException("temporary dispatcher failure");
        });
        AgentTaskService service = new AgentTaskService(
            eventBus,
            eventStore,
            repository,
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
        Field queueCoordinatorField = AgentTaskService.class.getDeclaredField("queueCoordinator");
        queueCoordinatorField.setAccessible(true);
        queueCoordinatorField.set(service, coordinator);

        AgentTaskResponse response = service.submit(
            idempotentRequest("message-staged", "diagnose Oracle health"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(lifecycle).containsExactly(
            "save:SUBMITTING", "event:QUESTION", "save:PENDING", "dispatch");
    }

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
    void taskRuntimeAttributesDoNotImposeConfiguredExecutionTimeout() throws Exception {
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
            .doesNotContainKey("__agentTimeoutMs");
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
    void cleanDisplayAnswerDoesNotTruncateLongUserFacingAnswer() {
        String answer = "# 客户全景分析报告\n\n" + "完整分析段落。".repeat(1200);

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .isEqualTo(answer)
            .hasSize(answer.length())
            .doesNotContain("truncated");
    }

    @Test
    void cleanDisplayAnswerRemovesInternalEvidenceMarkers() {
        String answer = """
            # 客户交易分析

            - 总资产 847,174.25 元 [evidence: tool://mcp_chatchat_mcp_server_api_template_execute#result=3/child=1, tool://mcp_chatchat_mcp_server_api_template_execute#result=3/child=2]。
            - 当日交易活跃 [EVIDENCE: tool://api_template_execute#result=4]，但正文应保留。
            """;

        String cleaned = AgentTaskService.cleanDisplayAnswer(answer);

        assertThat(cleaned)
            .contains("总资产 847,174.25 元。")
            .contains("当日交易活跃，但正文应保留。")
            .doesNotContainIgnoringCase("[evidence:")
            .doesNotContain("tool://");
    }

    @Test
    void compileExecutionResultPreservesLongReportBeforeArtifactExternalization() throws Exception {
        AgentTaskService service = taskService(
            mock(AgentEventBus.class), mock(AgentEventStore.class), mock(AgentTaskLatestRepository.class),
            mock(TaskConfirmRepository.class), new ObjectMapper());
        String fullAnswer = "# Complete report\n\n"
            + "| security | market value | profit |\n|---|---:|---:|\n| 600000 | 100.00 | +1.00 |\n".repeat(180)
            + "\nREPORT_TAIL_MARKER";
        assertThat(fullAnswer.length()).isGreaterThan(8_000);
        InteractionResponse response = InteractionResponse.builder().answer(fullAnswer).build();
        Method compile = AgentTaskService.class.getDeclaredMethod("compileExecutionResult", InteractionResponse.class);
        compile.setAccessible(true);

        Object contract = compile.invoke(service, response);
        Method answerSummary = contract.getClass().getDeclaredMethod("answerSummary");
        answerSummary.setAccessible(true);

        assertThat(answerSummary.invoke(contract))
            .isEqualTo(fullAnswer)
            .asString()
            .endsWith("REPORT_TAIL_MARKER");
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

    @Test
    void pollResultSkipsQueuedRuntimeEventsAndReturnsAnswer() throws Exception {
        AgentEventBus eventBus = mock(AgentEventBus.class);
        AgentEventStore eventStore = mock(AgentEventStore.class);
        AgentTaskLatestRepository repository = mock(AgentTaskLatestRepository.class);
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-result-filter");
        task.setTenantId("tenant-1");
        task.setSessionId("session-1");
        task.setStatus("SUCCESS");
        AgentEvent runtimeStarted = AgentEvent.builder()
            .taskId(task.getTaskId()).type("RUNTIME_STARTED").status("RUNNING").build();
        AgentEvent answer = AgentEvent.builder()
            .taskId(task.getTaskId()).type("ANSWER").status("SUCCESS")
            .payload("{\"answer\":\"final\"}").build();
        when(repository.findById(task.getTaskId())).thenReturn(Optional.of(task));
        when(eventBus.pollResult(org.mockito.ArgumentMatchers.eq(task.getTaskId()), anyLong(),
            org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
            .thenReturn(runtimeStarted, answer);
        AgentTaskService service = taskService(
            eventBus, eventStore, repository, mock(TaskConfirmRepository.class), new ObjectMapper());

        Optional<AgentEvent> result = service.pollResult("tenant-1", task.getTaskId(), 1000);

        assertThat(result).contains(answer);
        verify(eventBus, times(2)).pollResult(
            org.mockito.ArgumentMatchers.eq(task.getTaskId()), anyLong(),
            org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
    }

    private AgentTaskSubmitRequest idempotentRequest(String key, String query) {
        AgentTaskSubmitRequest request = new AgentTaskSubmitRequest();
        request.setTenantId("tenant-1");
        request.setUserId("user-1");
        request.setAgentId("general");
        request.setSessionId("session-1");
        request.setIdempotencyKey(key);
        request.setQuery(query);
        return request;
    }

    private AgentTaskLatestEntity taskFor(AgentTaskSubmitRequest request) {
        AgentTaskLatestEntity task = new AgentTaskLatestEntity();
        task.setTaskId("task-" + request.getIdempotencyKey());
        task.setTenantId(request.getTenantId());
        task.setUserId(request.getUserId());
        task.setAgentId(request.getAgentId());
        task.setSessionId(request.getSessionId());
        task.setIdempotencyKey(request.getIdempotencyKey());
        task.setQuestion(request.getQuery());
        task.setStatus("PENDING");
        task.setCreateTime(Instant.now());
        task.setUpdateTime(Instant.now());
        return task;
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
