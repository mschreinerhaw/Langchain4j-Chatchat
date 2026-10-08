package com.chatchat.agents.orchestration.lifecycle;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.observation.AgentObservationPipeline;
import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.agents.runtime.plan.execution.AgentPlanSuspendedException;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.store.AgentRunStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunLifecycleCoordinatorTest {

    @Test
    void failedAnswerAuditIsPersistedAsPartialPublicOutcome() {
        var store = new com.chatchat.agents.runtime.store.InMemoryAgentRunStore();
        var adapter = new AgentRunResultAdapter(store, mock(AgentObservationPipeline.class));
        var request = AgentRunRequest.builder().runId("audit-failed-run").build();

        var result = new AgentRunLifecycleCoordinator(store, adapter).execute(request,
            ignored -> new AgentOrchestrator.AgentExecutionResult("Unverified analysis", java.util.List.of(),
                java.util.Map.of("claimCoverageStatus", "FAIL", "answerClaimAuditPassed", false)));

        org.assertj.core.api.Assertions.assertThat(result.status())
            .isEqualTo(com.chatchat.agents.runtime.run.AgentRunStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(result.metadata())
            .containsEntry("publicStatus", "PARTIAL_SUCCESS")
            .containsEntry("answerStatus", "PARTIAL");
        var persisted = store.find("audit-failed-run").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(persisted.finishedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(persisted.metadata())
            .containsEntry("publicStatus", "PARTIAL_SUCCESS");
    }

    @Test
    void returningRunningWithoutContinuationCannotLeaveAnOrphanRun() {
        var store = new com.chatchat.agents.runtime.store.InMemoryAgentRunStore();
        var adapter = mock(AgentRunResultAdapter.class);
        var request = AgentRunRequest.builder().runId("orphan-run").build();
        when(adapter.toAgentRunResult(org.mockito.ArgumentMatchers.eq("orphan-run"),
            org.mockito.ArgumentMatchers.any())).thenReturn(com.chatchat.agents.runtime.AgentRunResult.builder()
                .runId("orphan-run").status(com.chatchat.agents.runtime.run.AgentRunStatus.RUNNING).build());
        var result = new AgentRunLifecycleCoordinator(store, adapter).execute(request, ignored -> null);
        org.assertj.core.api.Assertions.assertThat(result.status())
            .isEqualTo(com.chatchat.agents.runtime.run.AgentRunStatus.FAILED);
        var persisted = store.find("orphan-run").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(persisted.finishedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(persisted.events())
            .extracting(event -> event.type().name()).contains("RUN_FAILED").doesNotContain("RUN_COMPLETED");
    }

    @Test
    void planSuspensionDoesNotFailTheRun() {
        AgentRunStore store = mock(AgentRunStore.class);
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        AgentRun run = mock(AgentRun.class);
        AgentRunRequest request = AgentRunRequest.builder().runId("run-1").build();
        AgentPlanPipelineContinuation continuation = mock(AgentPlanPipelineContinuation.class);
        AgentPlanSuspendedException suspension = new AgentPlanSuspendedException(continuation);
        when(store.start(request)).thenReturn(run);

        AgentRunLifecycleCoordinator coordinator = new AgentRunLifecycleCoordinator(store, adapter);
        AgentPlanSuspendedException thrown = assertThrows(AgentPlanSuspendedException.class,
            () -> coordinator.execute(request, ignored -> { throw suspension; }));

        assertSame(suspension, thrown);
        verify(store, never()).fail("run-1", suspension);
        verify(store, never()).complete(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    }
}
