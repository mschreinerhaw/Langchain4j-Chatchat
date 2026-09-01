package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeToolCallingPlannerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void convertsNativeCallIntoGovernedAgentDecisionWithoutExecutingTool() throws Exception {
        ToolRegistry registry = registry("document_search");
        NativeToolCallingPlanner planner = new NativeToolCallingPlanner(registry, mapper, true, 8);
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = model(request -> {
            captured.set(request);
            return response("call-1", "document_search", "{\"query\":\"runtime architecture\"}");
        });

        Optional<AgentDecision> result = planner.decide(
            model, "Find the runtime architecture", "Follow enterprise policy",
            List.of("document_search"), List.of(), true);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().action()).isEqualTo("tool");
        assertThat(result.orElseThrow().toolName()).isEqualTo("document_search");
        assertThat(result.orElseThrow().arguments()).containsEntry("query", "runtime architecture");
        assertThat(result.orElseThrow().executionPlan())
            .containsEntry("decisionProtocol", "native_function_calling")
            .containsEntry("nativeToolCallGoverned", true);
        assertThat(captured.get().toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        assertThat(captured.get().toolSpecifications()).hasSize(1);
        JsonNode schema = mapper.readTree(captured.get().toolSpecifications().get(0).toJson());
        assertThat(schema.at("/parameters/required/0").asText()).isEqualTo("query");
        assertThat(schema.at("/parameters/properties/query/type").asText()).isEqualTo("string");
        verify(registry, never()).executeEnhancedTool(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsProviderToolNameOutsideRuntimeCandidateSet() {
        ToolRegistry registry = registry("document_search");
        NativeToolCallingPlanner planner = new NativeToolCallingPlanner(registry, mapper, true, 8);

        Optional<AgentDecision> result = planner.decide(
            model(request -> response("call-2", "invented_tool", "{}")),
            "Search", null, List.of("document_search"), List.of(), true);

        assertThat(result).isEmpty();
    }

    @Test
    void disabledModeDoesNotContactModel() {
        ToolRegistry registry = registry("document_search");
        NativeToolCallingPlanner planner = new NativeToolCallingPlanner(registry, mapper, false, 8);
        AtomicInteger calls = new AtomicInteger();

        Optional<AgentDecision> result = planner.decide(model(request -> {
            calls.incrementAndGet();
            return response("call-3", "document_search", "{}");
        }), "Search", null, List.of("document_search"), List.of(), true);

        assertThat(result).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void doesNotHideRuntimeDeadlineFailureBehindPlannerFallback() {
        ToolRegistry registry = registry("document_search");
        NativeToolCallingPlanner planner = new NativeToolCallingPlanner(registry, mapper, true, 8);

        assertThatThrownBy(() -> planner.decide(model(request -> {
            throw new AgentDeadlineExceededException("deadline");
        }), "Search", null, List.of("document_search"), List.of(), true))
            .isInstanceOf(AgentDeadlineExceededException.class);
    }

    private ToolRegistry registry(String toolName) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(toolName)).thenReturn(true);
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("Document search")
            .description("Search trusted documents")
            .agentCompatible(true)
            .parameters(List.of(ToolParameter.builder()
                .name("query")
                .type("string")
                .description("Search query")
                .required(true)
                .metadata(Map.of("minLength", 1))
                .build()))
            .build());
        return registry;
    }

    private ChatModel model(java.util.function.Function<ChatRequest, ChatResponse> handler) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return handler.apply(request);
            }
        };
    }

    private ChatResponse response(String id, String toolName, String arguments) {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
            .id(id)
            .name(toolName)
            .arguments(arguments)
            .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(call)).build();
    }
}
