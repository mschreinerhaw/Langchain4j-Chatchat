package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.planning.AgentDecision;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolWorkflowRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentPlannerNativeToolCallingTest {

    @Test
    void usesNativeCallingForFirstSingleMandatoryDirectToolDecision() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool("document_search")).thenReturn(true);
        when(registry.getWorkflowRole("document_search")).thenReturn(ToolWorkflowRole.DIRECT);
        when(registry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .description("Search trusted documents")
            .agentCompatible(true)
            .parameters(List.of(ToolParameter.builder()
                .name("query").type("string").required(true).build()))
            .build());
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setNativeToolCallingEnabled(true);
        AgentPlanner planner = new AgentPlanner(registry, new ObjectMapper(), properties);
        AtomicInteger nativeRequests = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                nativeRequests.incrementAndGet();
                ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id("native-call-1")
                    .name("document_search")
                    .arguments("{\"query\":\"MCP communication\"}")
                    .build();
                return ChatResponse.builder().aiMessage(AiMessage.from(call)).build();
            }
        };

        PlannerExecutionResult result = planner.decideNextAction(
            model,
            "How does MCP communicate?",
            "Use trusted tools",
            List.of("document_search"),
            List.of(),
            List.of(),
            List.of(),
            List.of("document_search"),
            true,
            false,
            null,
            null,
            Map.of()
        );

        AgentDecision decision = result.decision();
        assertThat(nativeRequests).hasValue(1);
        assertThat(decision.action()).isEqualTo("tool");
        assertThat(decision.toolName()).isEqualTo("document_search");
        assertThat(decision.arguments()).containsEntry("query", "MCP communication");
        assertThat(decision.executionPlan())
            .containsEntry("decisionProtocol", "native_function_calling")
            .containsEntry("nativeToolCallGoverned", true);
    }
}
