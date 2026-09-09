package com.chatchat.agents.orchestration;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDrivenTemplateParameterBinderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bindsEntityCollectionAcrossTemplatesThroughOneNativeFunctionCall() throws Exception {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        ChatModel model = model(request -> {
            captured.set(request);
            return response("native-1", """
                {"bindings":[
                  {"template_id":"assets","binding_id":"a-1","arguments":{"subject":"070200046604"}},
                  {"template_id":"assets","binding_id":"a-2","arguments":{"subject":"070200046605"}},
                  {"template_id":"trades","binding_id":"t-1","arguments":{"subject":"070200046604"}},
                  {"template_id":"trades","binding_id":"t-2","arguments":{"subject":"070200046605"}}
                ]}
                """);
        });
        List<Map<String, Object>> templates = List.of(template("assets"), template("trades"));

        SchemaDrivenTemplateParameterBinder.BindingResult result =
            new SchemaDrivenTemplateParameterBinder(mapper).bind(model,
                "分析客户号070200046604、070200046605的资产和交易", templates,
                List.of("assets", "trades")).orElseThrow();

        assertThat(result.protocols()).hasSize(4);
        assertThat(result.protocols().toString())
            .contains("native_function_calling", "070200046604", "070200046605", "a-1", "t-2");
        assertThat(captured.get().toolChoice()).isEqualTo(ToolChoice.REQUIRED);
        JsonNode schema = mapper.readTree(captured.get().toolSpecifications().get(0).toJson());
        assertThat(schema.at("/name").asText()).isEqualTo("bind_template_parameters");
        assertThat(schema.at("/parameters/properties/bindings/maxItems").asInt()).isEqualTo(32);
        assertThat(schema.at("/parameters/properties/bindings/items/oneOf")).hasSize(2);
    }

    @Test
    void rejectsInventedValuesAndIncompleteTemplateCoverage() {
        ChatModel model = model(request -> response("native-2", """
            {"bindings":[{"template_id":"assets","arguments":{"subject":"invented"}}]}
            """));

        assertThat(new SchemaDrivenTemplateParameterBinder(mapper).bind(model,
            "分析070200046604", List.of(template("assets"), template("trades")),
            List.of("assets", "trades"))).isEmpty();
    }

    @Test
    void retriesAutoWhenProviderRejectsRequiredToolChoice() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ToolChoice> lastChoice = new AtomicReference<>();
        ChatModel model = model(request -> {
            lastChoice.set(request.toolChoice());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalArgumentException("thinking mode rejects REQUIRED");
            }
            return response("native-auto", """
                {"bindings":[{"template_id":"assets","arguments":{"subject":"070200046604"}}]}
                """);
        });

        assertThat(new SchemaDrivenTemplateParameterBinder(mapper).bind(model,
            "分析070200046604", List.of(template("assets")), List.of("assets"))).isPresent();
        assertThat(calls).hasValue(2);
        assertThat(lastChoice.get()).isEqualTo(ToolChoice.AUTO);
    }

    @Test
    void completesEntityMatrixForTemplatesAddedByCoverageAuditWithoutBusinessFieldNames() {
        List<Map<String, Object>> reviewed = List.of(
            protocol("assets", "070200046604"),
            protocol("assets", "070200046605"));

        SchemaDrivenTemplateParameterBinder.BindingResult result =
            new SchemaDrivenTemplateParameterBinder(mapper)
                .completeFromReviewedProtocols(
                    List.of(template("assets"), template("trades")),
                    List.of("assets", "trades"), reviewed)
                .orElseThrow();

        assertThat(result.protocols()).hasSize(4);
        assertThat(result.protocols().stream()
            .filter(item -> "trades".equals(item.get("template_id")))
            .map(item -> String.valueOf(((Map<?, ?>) ((Map<?, ?>) item.get("arguments"))
                .get("subject")).get("value")))
            .toList())
            .containsExactly("070200046604", "070200046605");
        assertThat(result.mode()).isEqualTo("schema_reused_reviewed_protocol");
    }

    private Map<String, Object> protocol(String templateId, String value) {
        return Map.of(
            "protocol_version", "template_parameter_protocol_v2",
            "template_id", templateId,
            "arguments", Map.of("subject", Map.of(
                "value", value,
                "source", "user_query",
                "evidence", Map.of("quote", value))),
            "unresolved_parameters", List.of());
    }

    private Map<String, Object> template(String id) {
        return Map.of(
            "templateId", id,
            "parameterSchema", Map.of(
                "type", "object",
                "properties", Map.of("subject", Map.of("type", "string")),
                "required", List.of("subject")));
    }

    private ChatModel model(java.util.function.Function<ChatRequest, ChatResponse> handler) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return handler.apply(request);
            }
        };
    }

    private ChatResponse response(String id, String arguments) {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
            .id(id).name(SchemaDrivenTemplateParameterBinder.FUNCTION_NAME)
            .arguments(arguments).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(call)).build();
    }
}
