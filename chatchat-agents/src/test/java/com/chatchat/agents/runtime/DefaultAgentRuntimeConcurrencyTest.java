package com.chatchat.agents.runtime;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentRuntimeConcurrencyTest {

    @Test
    void concurrentMultiTenantRunsDoNotCompleteBeforeTheirOwnFinalSummaries() throws Exception {
        int tenantCount = 4;
        int requestCount = 12;
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        CountDownLatch summaryStarted = new CountDownLatch(requestCount);
        CountDownLatch releaseSummary = new CountDownLatch(1);
        when(orchestrator.execute(any(AgentRunRequest.class))).thenAnswer(invocation -> {
            AgentRunRequest request = invocation.getArgument(0);
            runStore.start(request);
            summaryStarted.countDown();
            assertThat(releaseSummary.await(5, TimeUnit.SECONDS)).isTrue();
            AgentRunResult result = AgentRunResult.builder()
                .runId(request.getRunId())
                .status(AgentRunStatus.COMPLETED)
                .answer("summary-" + request.getTenantId() + "-" + request.getRunId())
                .stopReason("final_answer")
                .metadata(Map.of("tenantId", request.getTenantId()))
                .build();
            AgentRun completed = runStore.complete(request.getRunId(), result);
            return result.withStatusAndEvents(completed.status(), completed.events());
        });
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore, executor);
            Map<String, String> expectedTenantByRun = IntStream.range(0, requestCount)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                    index -> "concurrent-run-" + index,
                    index -> "tenant-" + (index % tenantCount)
                ));
            List<AgentRunHandle> handles = IntStream.range(0, requestCount)
                .mapToObj(index -> runtime.submit(AgentRunRequest.builder()
                    .runId("concurrent-run-" + index)
                    .requestId("concurrent-request-" + index)
                    .tenantId("tenant-" + (index % tenantCount))
                    .userId("shared-user")
                    .query("concurrent runtime contract " + index)
                    .build()))
                .toList();

            assertThat(summaryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handles).allSatisfy(handle -> {
                assertThat(handle.completion()).isNotDone();
                AgentRun storedRun = runtime.find(handle.runId()).orElseThrow();
                assertThat(storedRun.status())
                    .isIn(AgentRunStatus.PENDING, AgentRunStatus.RUNNING);
                assertThat(storedRun.request().getTenantId())
                    .isEqualTo(expectedTenantByRun.get(handle.runId()));
            });

            releaseSummary.countDown();
            List<AgentRunResult> results = handles.stream()
                .map(handle -> handle.completion().orTimeout(5, TimeUnit.SECONDS).join())
                .toList();

            assertThat(results)
                .extracting(AgentRunResult::answer)
                .containsExactlyInAnyOrderElementsOf(handles.stream()
                    .map(handle -> "summary-" + expectedTenantByRun.get(handle.runId()) + "-" + handle.runId())
                    .toList());
            assertThat(results).allSatisfy(result -> {
                String expectedTenant = expectedTenantByRun.get(result.runId());
                assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
                assertThat(result.metadata()).containsEntry("tenantId", expectedTenant);
                assertThat(result.answer()).contains(expectedTenant).endsWith(result.runId());
            });
        } finally {
            releaseSummary.countDown();
            executor.shutdownNow();
        }
    }
}
