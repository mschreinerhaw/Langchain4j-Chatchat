package com.chatchat.agents.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledTemplateBindingTest {
    @Test void completeIdentityCatalogSurvivesLargeCandidateDetails() throws Exception {
        var engine = mock(AgentOrchestrationEngine.class, CALLS_REAL_METHODS);
        var field = AgentOrchestrationEngine.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(engine, new ObjectMapper());
        var candidates = java.util.stream.IntStream.range(0, 20).mapToObj(i -> Map.of(
            "templateId", "opaque-" + i, "name", "Capability " + i,
            "description", "large details ".repeat(2000))).toList();
        var method = AgentOrchestrationEngine.class.getDeclaredMethod("candidateIdentityCatalog", Object.class);
        method.setAccessible(true);
        String catalog = (String) method.invoke(engine, payload(Map.of("templates", candidates), Map.of()));
        var parsed = new ObjectMapper().readTree(catalog);
        assertThat(parsed).hasSize(20);
        assertThat(parsed.get(19).get("templateId").asText()).isEqualTo("opaque-19");
        assertThat(catalog).doesNotContain("large details");
    }
    @Test void typedDiscoveryStageUsesGovernedDataAndIgnoresRawTransportStage() {
        var navigator = new com.chatchat.agents.runtime.plan.protocol.ToolProtocolPayloadNavigator();
        var role = com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY;
        assertThat(navigator.discoveryRole(payload(Map.of("schemaVersion", "asset_query_result.v1"),
            Map.of("schemaVersion", "template_query_result.v1")), role))
            .isEqualTo(com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY);
    }
    @Test void typedGovernedPayloadDeliversSchemaToTheUnifiedSelectionStep() throws Exception {
        var data=Map.of("templates",List.of(Map.of("templateId","approved")));
        var result=selectPayload(Map.of("structuredContent",payload(data,Map.of())),"approved",true);
        assertThat(result.status()).isEqualTo("SELECTED");assertThat(result.protocols()).hasSize(1);
    }

    @Test void rawTransportTemplatesCannotOverrideTheGovernedCandidateSet() throws Exception {
        var raw=Map.of("templates",List.of(Map.of("templateId","raw-only")));
        assertThat(selectPayload(payload(Map.of("templates",List.of()),raw),"raw-only",true).status()).isEqualTo("NO_CANDIDATES");
    }

    private Object payload(Object data, Object raw) {
        return new com.chatchat.common.mcp.runtime.McpAnalysisPayload(null, "request", "service", "tool",
            "SUCCESS", null, null, null, null, Map.of(), data, raw, null, null, false, null, Map.of());
    }

    @SuppressWarnings("unchecked")
    private SchemaDrivenTemplateParameterBinder.SelectionResult selectPayload(Object payload,String id,boolean nativeResponse) throws Exception {
        var engine=mock(AgentOrchestrationEngine.class,CALLS_REAL_METHODS);
        var field=AgentOrchestrationEngine.class.getDeclaredField("objectMapper");field.setAccessible(true);field.set(engine,new ObjectMapper());
        var method=AgentOrchestrationEngine.class.getDeclaredMethod("findCandidateMaps",Object.class,String.class,int.class);
        method.setAccessible(true);
        var candidates=(List<Map<String,Object>>)method.invoke(engine,payload,"templates",0);
        var model=mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(nativeResponse
            ?AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().name(SchemaDrivenTemplateParameterBinder.SELECTION_FUNCTION)
                .arguments(new ObjectMapper().writeValueAsString(Map.of("status","SELECTED","selections",List.of(Map.of("templateId",id,"parameters",Map.of()))))).build())
            :AiMessage.from("free-form arguments")).build());
        return new SchemaDrivenTemplateParameterBinder(new ObjectMapper()).selectAndBind(model,"C-1",candidates);
    }

    @Test void reviewerArgumentsCannotBypassTheDesignatedNativeSelectionContract() throws Exception {
        var data=Map.of("templates",List.of(Map.of("templateId","approved","parameterSchema",Map.of("type","object",
            "properties",Map.of("subject",Map.of("type","string")),"required",List.of("subject")))));
        var result=selectPayload(data,"approved",false);
        assertThat(result.status()).isEqualTo("INVALID_MODEL_RESPONSE");assertThat(result.protocols()).isEmpty();
    }
}
