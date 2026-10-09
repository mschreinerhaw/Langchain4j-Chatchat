package com.chatchat.api.runtime;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.evidence.ExecutionTraceRetrievalPort;
import com.chatchat.common.tool.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeTraceToolRegistrarTest {
    @Test void publishesReadOnlyCapabilityAndIgnoresOwnerArguments() {
        var registry = mock(ToolRegistry.class);
        var traces = mock(ExecutionTraceRetrievalPort.class);
        new RuntimeTraceToolRegistrar(registry, traces).register();
        var tool = ArgumentCaptor.forClass(ToolRegistry.EnhancedTool.class);
        verify(registry).registerTool(eq(RuntimeTraceToolRegistrar.TOOL_NAME), any(), tool.capture());
        assertThat(tool.getValue().getMetadata().isRequiresAuth()).isTrue();
        assertThat(tool.getValue().getMetadata().getOperationType()).isEqualTo("read");
        var input = ToolInput.builder().userId("owner").context(Map.of("tenantId", "tenant"))
            .parameters(Map.of("runId", "source", "query", "result", "tenantId", "foreign", "userId", "foreign")).build();
        when(traces.search(any(), anyString(), anyInt())).thenReturn(List.of());
        assertThat(tool.getValue().execute(input).isSuccess()).isTrue();
        verify(traces).search(new KernelDataScope("tenant", "owner", null, null, "source", null, Map.of()), "result", 20);
        input.setUserId(null);
        assertThat(tool.getValue().execute(input).isSuccess()).isFalse();
        verifyNoMoreInteractions(traces);
    }
}
