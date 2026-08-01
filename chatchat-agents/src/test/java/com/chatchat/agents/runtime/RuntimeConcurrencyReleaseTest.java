package com.chatchat.agents.runtime;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConcurrencyReleaseTest {

    @Test
    void circuitOpeningStopsConfiguredRetriesInsteadOfAmplifyingFailure() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("storm_tool")).thenReturn(ToolMetadata.builder().id("storm_tool").title("storm").build());
        AtomicInteger remoteCalls = new AtomicInteger();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            remoteCalls.incrementAndGet();
            return ToolOutput.failure("upstream unavailable");
        });
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(5);
        properties.setCircuitBreakerFailureThreshold(2);
        ToolRuntimeService service = new ToolRuntimeService(registry, new ObjectMapper(), properties, List.of(), List.of());

        ToolRuntimeExecution result = service.execute(request("storm-1"));

        assertThat(result.output().isSuccess()).isFalse();
        assertThat(remoteCalls).hasValue(2);
        assertThat(result.output().getMetadata()).containsEntry("retryable", false).containsEntry("circuitOpened", true);
        service.shutdown();
    }

    @Test
    void concurrentFailureWaveDoesNotCreateRetryStorm() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("storm_tool")).thenReturn(ToolMetadata.builder().id("storm_tool").title("storm").build());
        AtomicInteger remoteCalls = new AtomicInteger();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            remoteCalls.incrementAndGet();
            return ToolOutput.failure("upstream unavailable");
        });
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(5);
        properties.setCircuitBreakerFailureThreshold(2);
        properties.setExecutionMaxPoolSize(40);
        ToolRuntimeService service = new ToolRuntimeService(registry, new ObjectMapper(), properties, List.of(), List.of());
        ExecutorService callers = Executors.newFixedThreadPool(24);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            List<java.util.concurrent.Future<ToolRuntimeExecution>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) futures.add(callers.submit(() -> service.execute(request("wave"))));
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS).output().isSuccess()).isFalse();
        });

        assertThat(remoteCalls.get()).isLessThanOrEqualTo(26);
        assertThat(service.snapshot().openCircuits()).isEqualTo(1);
        callers.shutdownNow();
        service.shutdown();
    }

    @Test
    void cancellationCompletionRaceProducesExactlyOneTerminalEvent() throws Exception {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentRunRequest request = AgentRunRequest.builder().runId("cancel-race").query("race").requestId("race").timeoutMs(30L).build();
        when(orchestrator.execute(request)).thenAnswer(invocation -> {
            store.start(request);
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            AgentRunResult result = AgentRunResult.builder().runId("cancel-race").status(AgentRunStatus.CANCELLED).stopReason("cancelled").build();
            AgentRun terminal = store.complete("cancel-race", result);
            return result.withStatusAndEvents(terminal.status(), terminal.events());
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, store, executor);

        AgentRunHandle handle = runtime.submit(request);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        runtime.cancel(handle.runId());
        release.countDown();
        handle.completion().get(2, TimeUnit.SECONDS);

        AgentRun terminal = runtime.find("cancel-race").orElseThrow();
        assertThat(terminal.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(terminal.events().stream().filter(e -> e.type() == AgentRunEventType.RUN_CANCELLED)).hasSize(1);
        assertThat(terminal.events()).noneMatch(e -> e.type() == AgentRunEventType.RUN_COMPLETED);
        assertThat(runtime.snapshot().activeCancellationSignals()).isZero();
        executor.shutdownNow();
    }

    private ToolRuntimeRequest request(String id) {
        return ToolRuntimeRequest.builder().toolName("storm_tool").runtimeMode("agent_chat")
            .requestId(id).conversationId(id).userId("release-user").allowedTools(List.of("storm_tool"))
            .toolInput(ToolInput.builder().userId("release-user").parameters(Map.of()).build()).build();
    }
}
