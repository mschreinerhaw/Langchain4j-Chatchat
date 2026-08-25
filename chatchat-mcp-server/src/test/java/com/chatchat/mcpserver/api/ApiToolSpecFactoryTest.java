package com.chatchat.mcpserver.api;

import com.chatchat.common.knowledge.template.TemplateResolutionEvent;
import com.chatchat.common.knowledge.template.TemplateResolutionEventType;
import com.chatchat.common.bridge.api.McpApiBridge;
import com.chatchat.common.bridge.api.McpApiOperation;
import com.chatchat.common.bridge.api.McpApiCall;
import com.chatchat.common.bridge.api.McpApiResultStatus;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiToolSpecFactoryTest {

    @Test
    void implementsTheTypedApiExecutionBridgeContract() {
        ApiToolSpecFactory factory = new ApiToolSpecFactory(mock(ApiInvokeService.class),
            mock(ApiServiceConfigService.class), new ObjectMapper(),
            mock(AgentRuntimeGovernanceFactory.class), mock(McpToolConcurrencyManager.class),
            mock(StandardToolExecutionResultFactory.class));

        assertThat(factory).isInstanceOf(McpApiBridge.class);
        assertThat(factory.bridgeContract().operations())
            .containsExactly(McpApiOperation.TEMPLATE_EXECUTE.operationCode());
    }

    @Test
    void returnsStructuredEventsForMissingAndUnknownTemplateIds() {
        ApiServiceConfigService configs = mock(ApiServiceConfigService.class);
        when(configs.findByToolName("unknown_template")).thenReturn(Optional.empty());
        McpToolConcurrencyManager concurrency = mock(McpToolConcurrencyManager.class);
        when(concurrency.execute(anyString(), anyString(), anyMap(), any())).thenAnswer(invocation -> {
            Supplier<McpSchema.CallToolResult> operation = invocation.getArgument(3);
            return operation.get();
        });
        ApiToolSpecFactory factory = new ApiToolSpecFactory(
            mock(ApiInvokeService.class), configs, new ObjectMapper(),
            mock(AgentRuntimeGovernanceFactory.class), concurrency,
            mock(StandardToolExecutionResultFactory.class));
        var handler = factory.toGatewayToolSpecification().callHandler();

        McpSchema.CallToolResult missing = handler.apply(null, new McpSchema.CallToolRequest(
            ApiMcpToolPublisher.EXECUTE_TOOL_NAME, Map.of("requestId", "req-missing"), Map.of()));
        McpSchema.CallToolResult unknown = handler.apply(null, new McpSchema.CallToolRequest(
            ApiMcpToolPublisher.EXECUTE_TOOL_NAME,
            Map.of("templateId", "unknown_template", "requestId", "req-unknown"), Map.of()));

        assertResolution(missing, TemplateResolutionEventType.TEMPLATE_ID_MISSING, "req-missing");
        assertResolution(unknown, TemplateResolutionEventType.TEMPLATE_NOT_FOUND, "req-unknown");
    }

    @Test
    void executesThroughTheTypedBridgeAndPreservesTheCompleteApiPayload() {
        ApiServiceConfig config = mock(ApiServiceConfig.class);
        when(config.isEnabled()).thenReturn(true);
        ApiServiceConfigService configs = mock(ApiServiceConfigService.class);
        when(configs.findByToolName("customer_profile_v1")).thenReturn(Optional.of(config));
        ApiInvokeService invoke = mock(ApiInvokeService.class);
        ApiInvokeResult invoked = new ApiInvokeResult(true, 200, Map.of(),
            Map.of("customerId", "C-1", "rawEvidence", "complete-payload"), null, null);
        when(invoke.invoke(eq(config), anyMap())).thenReturn(invoked);
        StandardToolExecutionResultFactory results = mock(StandardToolExecutionResultFactory.class);
        when(results.fromApi(config, invoked)).thenReturn(Map.of(
            "success", true, "payload", invoked.body(), "statusCode", 200));
        ApiToolSpecFactory factory = new ApiToolSpecFactory(invoke, configs, new ObjectMapper(),
            mock(AgentRuntimeGovernanceFactory.class), mock(McpToolConcurrencyManager.class), results);

        var response = factory.communicate(McpApiCall.execute("customer_profile_v1",
            Map.of("customerId", "C-1"), Map.of(), "task-1", 0),
            KernelDataScope.system("execute-request"));

        assertThat(response.successful()).isTrue();
        assertThat(response.data().status()).isEqualTo(McpApiResultStatus.SUCCESS);
        assertThat(response.data().data().get("payload")).isEqualTo(invoked.body());
        assertThat(response.data().toPayload()).containsEntry("communicationRequestId", "execute-request");
    }

    private void assertResolution(McpSchema.CallToolResult result,
                                  TemplateResolutionEventType type,
                                  String requestId) {
        assertThat(result.isError()).isTrue();
        Map<?, ?> body = (Map<?, ?>) result.structuredContent();
        assertThat(body.get("schemaVersion")).isEqualTo("template_execution_resolution.v1");
        assertThat(body.get("status")).isEqualTo("RESOLUTION_REQUIRED");
        assertThat(body.get("event")).isInstanceOfSatisfying(TemplateResolutionEvent.class, event -> {
            assertThat(event.type()).isEqualTo(type);
            assertThat(event.requestId()).isEqualTo(requestId);
        });
    }
}
