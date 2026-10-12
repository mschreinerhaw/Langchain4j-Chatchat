package com.chatchat.agents.orchestration.tool;

import com.chatchat.agents.runtime.tool.*;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizedTemplateExecutionTest {
    @Test void analysisTaskMarkerCannotBypassObservedTemplateAndAssetContracts() {
        var registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata("executor")).thenReturn(ToolMetadata.builder().operationType("read").riskLevel("low").build());
        var arguments = mock(AgentToolArgumentResolver.class);
        when(arguments.enforceObservedAssetContinuity(anyString(), anyMap(), anyList())).thenAnswer(call -> call.getArgument(1));
        when(arguments.applyObservedTemplateContract(anyString(), anyMap(), anyList(), anyString()))
            .thenReturn(new LinkedHashMap<>(Map.of("template", "bound-template",
                "executionContext", Map.of("assetId", "authorized-asset"))));
        var runtime = mock(ToolRuntimeService.class);
        var output = ToolOutput.success(Map.of("value", 1));
        when(runtime.execute(any())).thenReturn(new ToolRuntimeExecution(output, null, null, "success", Map.of()));
        var observer = mock(ToolObservationBuilder.class);
        when(observer.buildSuccessObservation(anyString(), any(), anyString())).thenReturn("Runtime observation");
        var executor = new AgentToolExecutor(registry, runtime, arguments, observer);
        var modelArguments = new LinkedHashMap<String, Object>(Map.of("template", "registered-template", "parameters", Map.of("window", 10),
            AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER, true));
        executor.execute("executor", modelArguments, "conversation", "request", "authenticated-user", "tenant",
            List.of("executor"), Map.of(), List.of(), Map.of("modelNativeTask", true, "originalUserQuery", "Inspect authorized data"));
        verify(arguments).applyObservedTemplateContract(anyString(), eq(modelArguments), anyList(), eq("Inspect authorized data"));
        verify(arguments, never()).applyObservedTemplateContract(anyString(), anyMap(), anyList());
        verify(arguments).enforceObservedAssetContinuity(anyString(), anyMap(), anyList());
        var request = org.mockito.ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(runtime).execute(request.capture());
        assertThat(request.getValue().getToolInput().getParameters()).containsEntry("template", "bound-template")
            .containsEntry("executionContext", Map.of("assetId", "authorized-asset")).doesNotContainKey(AgentToolArgumentResolver.RUNTIME_OWNED_TEMPLATE_BATCH_MARKER);
        assertThat(request.getValue().getAttributes()).doesNotContainKey("runtimeOwnedTemplateBatch");
        assertThat(request.getValue().getTenantId()).isEqualTo("tenant");
        assertThat(request.getValue().getUserId()).isEqualTo("authenticated-user");
        assertThat(request.getValue().getAllowedTools()).containsExactly("executor");
    }
}
