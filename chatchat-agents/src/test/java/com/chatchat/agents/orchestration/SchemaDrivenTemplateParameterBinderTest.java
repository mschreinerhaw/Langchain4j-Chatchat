package com.chatchat.agents.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SchemaDrivenTemplateParameterBinderTest {
    final ObjectMapper json=new ObjectMapper();
    final SchemaDrivenTemplateParameterBinder binder=new SchemaDrivenTemplateParameterBinder(json);
    @Test void incompleteBusinessRequestCannotBindPublishedDefaults() {
        var model=mock(ChatModel.class);
        var result=binder.selectAndBind(model,"long untrusted material ".repeat(1000),List.of(template("assets")));
        assertThat(result.status()).isEqualTo("NEEDS_INPUT");assertThat(result.protocols()).isEmpty();
        assertThat(result.metrics()).containsEntry("selectionModelCalls",0);
        verifyNoInteractions(model);
    }

    @Test void unavailableModelCannotAuthorizeParameterFreeTemplates() {
        var result=binder.selectAndBind(null,"inspect",List.of(Map.of("templateId","fixed"),template("parameterized")));
        assertThat(result.status()).isEqualTo("MODEL_UNAVAILABLE");assertThat(result.protocols()).isEmpty();
    }

    @Test void unknownRequiredValuesAreUnresolvedWithoutForcingModelInvention() throws Exception {
        var model=model(List.of(selection("assets",Map.of())));
        var result=binder.selectAndBind(model,"inspect",List.of(template("assets")));
        assertThat(result.status()).isEqualTo("PARAMETERS_REQUIRED");
        assertThat(result.protocols().get(0)).containsEntry("arguments",Map.of()).containsEntry("unresolved_parameters",List.of("subject"));
        var request=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model).chat(request.capture());
        assertThat(request.getValue().toolSpecifications().get(0).toJson()).doesNotContain("\"required\":[\"subject\"]");
    }

    @Test void modelChoosesAnEmptySchemaTemplateInOneNativeCall() throws Exception {
        var model=model(List.of(selection("fixed",Map.of())));
        var result=binder.selectAndBind(model,"inspect",List.of(Map.of("templateId","fixed")));
        assertThat(result.status()).isEqualTo("SELECTED");assertThat(result.protocols()).hasSize(1);
        verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void freeFormReviewerArgumentsCannotReplaceTheNativeContract() {
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"arguments\":{\"subject\":\"C1\"}}")).build());
        var result=binder.selectAndBind(model,"C1",List.of(template("assets")));
        assertThat(result.status()).isEqualTo("INVALID_MODEL_RESPONSE");assertThat(result.protocols()).isEmpty();
        verify(model,times(2)).chat(any(ChatRequest.class));
    }

    @Test void modelExplicitlyBindsMultipleEntitiesAcrossTemplatesInOneCall() throws Exception {
        var model=model(List.of(selection("assets",Map.of("subject","C1")),selection("assets",Map.of("subject","C2")),
            selection("trades",Map.of("subject","C1")),selection("trades",Map.of("subject","C2"))));
        var result=binder.selectAndBind(model,"Compare C1 and C2",List.of(template("assets"),template("trades")));
        assertThat(result.protocols()).hasSize(4);
        var captured=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model).chat(captured.capture());
        assertThat(captured.getValue().toolChoice()).isEqualTo(ToolChoice.AUTO);
        var spec=json.readTree(captured.getValue().toolSpecifications().get(0).toJson());
        assertThat(spec.at("/name").asText()).isEqualTo(SchemaDrivenTemplateParameterBinder.SELECTION_FUNCTION);
        assertThat(spec.at("/parameters/properties/selections/maxItems").asInt()).isEqualTo(32);
        assertThat(spec.at("/parameters/properties/selections/items/oneOf")).hasSize(2);
    }

    @Test void inventedValuesCannotBecomeExecutionEvidence() throws Exception {
        var result=binder.selectAndBind(model(List.of(selection("assets",Map.of("subject","invented")))),
            "Analyze C1",List.of(template("assets"),template("trades")));
        assertThat(result.status()).isEqualTo("PARAMETERS_REQUIRED");
        assertThat(result.protocols().get(0)).containsEntry("arguments",Map.of());
        assertThat(result.selectedIds()).containsExactly("assets");
    }

    @Test void providerTransportFailureIsNotRetriedAsAProtocolCorrection() {
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("transport unavailable"));
        assertThat(binder.selectAndBind(model,"C1",List.of(template("assets"))).status()).isEqualTo("MODEL_UNAVAILABLE");
        verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void javaDoesNotCompleteAnUnselectedTemplateEntityMatrix() throws Exception {
        var result=binder.selectAndBind(model(List.of(selection("assets",Map.of("subject","C1")),
            selection("assets",Map.of("subject","C2")),selection("trades",Map.of("subject","C1")))),
            "Compare C1 and C2",List.of(template("assets"),template("trades")));
        assertThat(result.protocols()).hasSize(3);
        assertThat(result.protocols().stream().filter(p->"trades".equals(p.get("template_id"))).toList())
            .singleElement().satisfies(p->assertThat(((Map<?,?>)((Map<?,?>)p.get("arguments")).get("subject")).get("value")).isEqualTo("C1"));
    }

    @Test void differentlyNamedFieldsRemainBoundByTheModelToTheirOwnTemplate() throws Exception {
        var first=Map.<String,Object>of("templateId","assets","parameterSchema",Map.of("type","object",
            "properties",Map.of("customer",Map.of("type","string","default","catalog-example"))));
        var second=Map.<String,Object>of("templateId","trades","parameterSchema",Map.of("type","object",
            "properties",Map.of("account",Map.of("type","string","default","other-example"))));
        var result=binder.selectAndBind(model(List.of(selection("assets",Map.of("customer","C1")),
            selection("trades",Map.of("account","C1")))),"C1",List.of(first,second));
        assertThat(result.protocols()).hasSize(2);
        assertThat(result.protocols().toString()).contains("customer","account","C1").doesNotContain("catalog-example","other-example");
    }

    @Test void T09_oneMissingRequiredBindingDoesNotBlockIndependentTemplates() throws Exception {
        var candidates=List.of(template("blocked"),template("ready"));
        var model=model(List.of(selection("blocked",Map.of()),selection("ready",Map.of("subject","C1"))));
        var result=binder.selectAndBind(model,"Analyze C1",candidates);
        assertThat(result.status()).isEqualTo("SELECTED");assertThat(result.selectedIds()).containsExactly("blocked","ready");
        assertThat(result.protocols().get(0)).containsKey("binding_error");
        assertThat(result.protocols().get(1)).containsEntry("binding_status","READY").doesNotContainKey("binding_error");
        var evaluated=new com.chatchat.agents.runtime.plan.selection.EvidenceBasedTemplateCandidateEvaluator().evaluate(
            Map.of("templates",candidates),Map.of("selectedTemplateIds",result.selectedIds(),"parameterProtocols",result.protocols()));
        assertThat(evaluated.selectedIds()).containsExactly("blocked","ready");
        verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void T13_defaultsRemainExecutableButAreNeverMarkedAsVerifiedUserScope() throws Exception {
        var candidate=Map.<String,Object>of("templateId","scoped","parameterSchema",Map.of("type","object",
            "properties",Map.of("subject",Map.of("type","string","default","catalog-B")),"required",List.of("subject")));
        var model=model(List.of(selection("scoped",Map.of("subject","unsubstantiated"))));
        var selected=binder.selectAndBind(model,"Analyze requested-A",List.of(candidate));
        assertThat(selected.status()).isEqualTo("SELECTED");
        assertThat(selected.protocols().get(0)).containsEntry("unresolved_parameters",List.of("subject"));
        var result=new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge().prepare(
            new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge.BridgeRequest("executor",null,"scoped",candidate,
                Map.of(),selected.protocols().get(0),false,true,
                new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge.EvidenceContext("Analyze requested-A",Map.of())));
        assertThat(result.parameters()).containsEntry("subject","catalog-B");
        Map<?,?> audit=(Map<?,?>)result.executorInput().get(com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge.BINDING_AUDIT);
        assertThat(audit.get("scopeStatus")).isEqualTo("REQUIRES_SCOPE_VERIFICATION");
        assertThat((List<?>)audit.get("droppedOverrideFields")).anyMatch("subject"::equals);
        assertThat(result.parameterEvidence().get("subject").source()).isEqualTo("template_default");
    }

    @Test void T14_unknownFieldsAndMissingDefaultValuesNeverTriggerAnotherExtraction() throws Exception {
        var candidate=Map.<String,Object>of("templateId","defaulted","parameterSchema",Map.of("type","object",
            "properties",Map.of("limit",Map.of("type","integer","default",5))));
        var model=model(List.of(selection("defaulted",Map.of("unknown","not-declared"))));
        var result=binder.selectAndBind(model,"inspect",List.of(candidate));
        assertThat(result.status()).isEqualTo("SELECTED");
        assertThat(result.protocols().get(0)).containsEntry("arguments",Map.of()).containsEntry("binding_status","READY");
        verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void T10_modelMayReturnEmptyParametersAndNeedsInputForDefaultedTemplates() throws Exception {
        var model=mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
            .id("selection").name(SchemaDrivenTemplateParameterBinder.SELECTION_FUNCTION)
            .arguments(json.writeValueAsString(Map.of("status","NEEDS_INPUT","selections",List.of(selection("defaulted",Map.of()))))).build())).build());
        var result=binder.selectAndBind(model,"inspect",List.of(Map.of("templateId","defaulted","parameterSchema",Map.of(
            "properties",Map.of("limit",Map.of("type","integer","default",5)),"required",List.of("limit")))));
        assertThat(result.status()).isEqualTo("SELECTED");verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void completeUpstreamRecordsetsBindByReferenceWithoutCopyingPreview() throws Exception {
        var rows=java.util.stream.IntStream.range(0,11).mapToObj(i->Map.<String,Object>of("amount",i)).toList();
        var recordset=Map.<String,Object>of("rows",rows,"columns",List.of(Map.of("name","amount")),"complete",true,"possiblyTruncated",false);
        var output=Map.<String,Object>of("data",Map.of("data",recordset));
        var context=Map.<String,Object>of(TemplateRecordsetBindings.OUTPUTS,Map.of("7",output));
        var template=Map.<String,Object>of("templateId","analysis","parameterSchema",Map.of("type","object",
            "properties",Map.of("input_rows",Map.of("type","array","items",Map.of("type","object"))),"required",List.of("input_rows")));
        var selection=Map.<String,Object>of("templateId","analysis","parameters",Map.of(),"parameterBindings",
            Map.of("input_rows",Map.of("stepId",7,"outputPath","$.data.data.rows")));
        var model=model(List.of(selection));
        var result=binder.selectAndBind(model,"Analyze the actual query results",List.of(template),context);
        assertThat(result.status()).isEqualTo("SELECTED");
        var argument=(Map<?,?>)((Map<?,?>)result.protocols().get(0).get("arguments")).get("input_rows");
        assertThat(argument.get("value")).isEqualTo(rows);assertThat(argument.get("source")).isEqualTo("tool_result");
        var executionProtocol=new java.util.LinkedHashMap<String,Object>(result.protocols().get(0));
        executionProtocol.put("step_id",8);
        var bridged=new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge().prepare(
            new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge.BridgeRequest("executor",8,"analysis",template,
                Map.of(),executionProtocol,false,true,
                new com.chatchat.agents.runtime.toolcall.TemplateInvocationBridge.EvidenceContext("Analyze the actual query results",Map.of(7,output))));
        assertThat(bridged.parameters().get("input_rows")).isEqualTo(rows);
        var capture=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model).chat(capture.capture());
        String prompt=capture.getValue().messages().toString();
        assertThat(prompt).contains("completedRecordsets","rowCount","outputPath").doesNotContain("completedStepOutputs");
        assertThat(((List<?>)TemplateRecordsetBindings.modelContext(context).get("completedRecordsets"))).hasSize(1);
        assertThat((List<?>)((Map<?,?>)((List<?>)TemplateRecordsetBindings.modelContext(context).get("completedRecordsets")).get(0)).get("preview")).hasSize(2);
    }

    @Test void unavailableTruncatedAndUnknownReferencesCannotUseDefaultsOrInventValues() throws Exception {
        var template=Map.<String,Object>of("templateId","analysis","parameterSchema",Map.of("type","object",
            "properties",Map.of("input_rows",Map.of("type","array","default",List.of(Map.of("amount",0))))));
        for(var context:List.of(Map.<String,Object>of(),Map.<String,Object>of(TemplateRecordsetBindings.OUTPUTS,Map.of("7",
                Map.of("rows",List.of(Map.of("amount",1)),"columns",List.of("amount"),"complete",false,"possiblyTruncated",true))))) {
            var selection=Map.<String,Object>of("templateId","analysis","parameters",Map.of(),"parameterBindings",
                Map.of("input_rows",Map.of("stepId",7,"outputPath","$.rows")));
            var result=binder.selectAndBind(model(List.of(selection)),"Analyze",List.of(template),context);
            assertThat(result.status()).isEqualTo("PARAMETERS_REQUIRED");
            assertThat(result.protocols().get(0)).containsKey("binding_error");
        }
        var valid=Map.<String,Object>of(TemplateRecordsetBindings.OUTPUTS,Map.of("7",Map.of("rows",List.of(Map.of("amount",1)),
            "columns",List.of("amount"),"complete",true,"possiblyTruncated",false)));
        for(var ref:List.of(Map.<String,Object>of("stepId",99,"outputPath","$.rows"),
                Map.<String,Object>of("stepId",7,"outputPath","$.rows[0]"),Map.<String,Object>of("stepId",7,"outputPath","$.rawData.rows"))) {
            var selection=Map.<String,Object>of("templateId","analysis","parameters",Map.of(),"parameterBindings",Map.of("input_rows",ref));
            assertThat(binder.selectAndBind(model(List.of(selection)),"Analyze",List.of(template),valid).status()).isEqualTo("PARAMETERS_REQUIRED");
        }
    }

    @Test void literalPreviewAndReferenceCannotCompeteForTheSameParameter() throws Exception {
        var candidate=Map.<String,Object>of("templateId","analysis","parameterSchema",Map.of("properties",Map.of("rows",Map.of("type","array"))));
        var result=binder.selectAndBind(model(List.of(Map.of("templateId","analysis","parameters",Map.of("rows",List.of()),
            "parameterBindings",Map.of("rows",Map.of("stepId",1,"outputPath","$.rows"))))),"Analyze",List.of(candidate));
        assertThat(result.status()).isEqualTo("PARAMETERS_REQUIRED");
    }

    private Map<String,Object> template(String id) {
        return Map.of("templateId",id,"parameterSchema",Map.of("type","object","required",List.of("subject"),
            "properties",Map.of("subject",Map.of("type","string"))));
    }
    private Map<String,Object> selection(String id,Map<String,Object> parameters) {
        return Map.of("templateId",id,"parameters",parameters);
    }
    private ChatModel model(List<Map<String,Object>> selections) throws Exception {
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from(ToolExecutionRequest.builder().name(SchemaDrivenTemplateParameterBinder.SELECTION_FUNCTION)
                .arguments(json.writeValueAsString(Map.of("status","SELECTED","selections",selections))).build())).build());
        return model;
    }
}
