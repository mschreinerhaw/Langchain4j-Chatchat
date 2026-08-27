package com.chatchat.agents.orchestration.retrieval;

import com.chatchat.agents.orchestration.retrieval.ModelAssistedContextParameterBridge;
import com.chatchat.agents.orchestration.retrieval.ModelAssistedRetrievalBridge;

import com.chatchat.agents.runtime.toolcall.ContextualToolArgumentResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelAssistedContextParameterBridgeTest {

    @Test
    void modelOnlyProposesEvidencePointerForMissingPublishedArgument() {
        String tool = "market_observation_query";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
            .id(tool)
            .metadata(Map.of("inputSchema", Map.of(
                "type", "object",
                "required", List.of("symbol"),
                "properties", Map.of("symbol", Map.of("type", "string"))
            )))
            .build());
        ChatModel model = mock(ChatModel.class);
        when(model.chat(contains("Missing required fields: [symbol]"))).thenReturn("""
            {"evidence":[{"parameter":"symbol","source":"completed_step","stepId":2,"outputPath":"$.positions[0].symbol"}]}
            """);
        ModelAssistedContextParameterBridge bridge =
            new ModelAssistedContextParameterBridge(registry, new ObjectMapper());

        Map<String, Object> result = bridge.propose(
            model, tool, Map.of(), new ModelAssistedRetrievalBridge.RetrievalEvidenceContext(
                "analyze holding", Map.of(2, Map.of(
                    "positions", List.of(Map.of("symbol", "600839"))))));

        assertThat(result).containsKey(ContextualToolArgumentResolver.MODEL_EVIDENCE_FIELD);
        verify(model).chat(contains("Never invent a value or field"));
    }
}
