package com.chatchat.api.runtime.coding.execution;

import com.chatchat.agents.runtime.coding.CodingJournal;
import com.chatchat.agents.runtime.coding.DurableCodingTaskService;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.mcp.python.McpPythonControlPlanePort;
import com.chatchat.common.runtime.coding.CodingTaskOutcome;
import com.chatchat.common.runtime.coding.CodingErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingPythonCancellationTest {
    @Test void deadlineStopStillCancelsExecutorWithoutAUserCancellationFlag() {
        var tasks = mock(DurableCodingTaskService.class);
        var port = mock(McpPythonControlPlanePort.class);
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        var stopped = mock(CodingTaskOutcome.class);
        var control = new CodingTaskOutcome.ExecutionControl(CodingErrorCode.DEADLINE_EXCEEDED,
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), false, true);
        when(stopped.executionControl()).thenReturn(control);
        when(tasks.cancel(scope, "task", "deadline")).thenReturn(stopped);
        when(tasks.ownership(scope, "task")).thenReturn(new CodingJournal.Lease("worker", Instant.now().plusSeconds(30), 2));
        new CodingPythonExecutionBridge(tasks, port, new ObjectMapper()).cancel(scope, "task", "admitted", "deadline");
        verify(port).cancel("tenant", "owner", "run", "task", "admitted", 2);
        verify(port, never()).get(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void nodeCannotPersistCancellationTombstonesForUnadmittedOperationIds() {
        var tasks = mock(DurableCodingTaskService.class);
        var port = mock(McpPythonControlPlanePort.class);
        var scope = new KernelDataScope("tenant", "owner", null, null, "run", null, Map.of());
        when(tasks.startedCodeOperations(scope, "task")).thenReturn(List.of("admitted"));
        when(tasks.ownership(scope, "task")).thenReturn(new CodingJournal.Lease("worker", Instant.now().plusSeconds(30), 2));
        var bridge = new CodingPythonExecutionBridge(tasks, port, new ObjectMapper());
        assertThrows(SecurityException.class, () -> bridge.cancelExecution(scope, "task", "invented"));
        verifyNoInteractions(port);
        bridge.cancelExecution(scope, "task", "admitted");
        verify(port).cancel("tenant", "owner", "run", "task", "admitted", 2);
    }
}
