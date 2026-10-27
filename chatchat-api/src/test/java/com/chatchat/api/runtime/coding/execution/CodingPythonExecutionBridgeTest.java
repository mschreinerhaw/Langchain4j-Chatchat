package com.chatchat.api.runtime.coding.execution;

import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class CodingPythonExecutionBridgeTest {
    @Test void observationFailureDoesNotChangeExecutionReply() {
        var tasks = mock(DurableCodingTaskService.class);
        var python = mock(McpPythonControlPlanePort.class);
        var runs = mock(AgentRunStore.class);
        ObjectProvider<AgentRunStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runs);
        when(runs.find("run")).thenReturn(Optional.of(mock(AgentRun.class)));
        var bridge = new CodingPythonExecutionBridge(tasks, python, new ObjectMapper());
        ReflectionTestUtils.setField(bridge, "progressRuns", provider);
        var scope = new KernelDataScope("tenant", "user", "request", "session", "run", null, Map.of());
        var reply = mock(McpPythonControlPlanePort.OperationView.class);
        when(reply.operationId()).thenReturn("op");
        when(reply.status()).thenReturn("RUNNING");
        when(reply.containerId()).thenReturn("container");
        when(python.get("tenant","user","run","task","op")).thenReturn(reply);
        doThrow(new IllegalStateException("event publisher unavailable")).when(runs).recordObservation(eq("run"),any());
        assertThat(bridge.get(scope,"task","op")).isSameAs(reply);
        verify(tasks).get(scope,"task");
        verify(python).get("tenant","user","run","task","op");
        verify(runs).recordObservation(eq("run"),argThat(observation ->
            Boolean.TRUE.equals(observation.metadata().get("executorConfirmed"))
                && "op".equals(observation.metadata().get("operationId"))));
        verifyNoMoreInteractions(python);
    }
}
