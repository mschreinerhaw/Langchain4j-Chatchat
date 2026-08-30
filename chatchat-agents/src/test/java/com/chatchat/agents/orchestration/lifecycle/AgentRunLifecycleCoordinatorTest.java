package com.chatchat.agents.orchestration.lifecycle;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.runtime.AgentRunRequest;
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
