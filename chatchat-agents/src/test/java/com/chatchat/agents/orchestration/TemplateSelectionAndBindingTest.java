package com.chatchat.agents.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TemplateSelectionAndBindingTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final SchemaDrivenTemplateParameterBinder binder=new SchemaDrivenTemplateParameterBinder(mapper);

    @Test void oneModelCallSelectsThreeOfEightTemplatesAndExtractsParameters() throws Exception {
        var candidates=new ArrayList<Map<String,Object>>();
        for(int i=0;i<8;i++)candidates.add(template("t"+i,null));
        var selections=List.of(selection("t1","C42"),selection("t3","C42"),selection("t7","C42"));
        var model=model(Map.of("status","SELECTED","selections",selections));
        var result=binder.selectAndBind(model,"Analyze customer C42 assets, trading and profit",candidates);
        assertThat(result.status()).isEqualTo("SELECTED");
        assertThat(result.selectedIds()).containsExactly("t1","t3","t7");
        assertThat(result.protocols()).hasSize(3).allSatisfy(protocol->
            assertThat(protocol.get("arguments").toString()).contains("C42","user_query"));
        verify(model,times(1)).chat(any(ChatRequest.class));
        verify(model,never()).chat(anyString());
    }

    @Test void multipleBoundAssetsAndTemplatesRemainModelChoices() throws Exception {
        var first=new LinkedHashMap<>(selection("a","C42"));first.put("assetId","asset-1");
        var second=new LinkedHashMap<>(selection("b","C42"));second.put("assetId","asset-2");
        var result=binder.selectAndBind(model(Map.of("status","SELECTED","selections",List.of(first,second))),
            "Compare customer C42 across sources",List.of(template("a","asset-1"),template("b","asset-2")));
        assertThat(result.selectedIds()).containsExactly("a","b");
    }

    @Test void explicitNoMatchDoesNotSelectTheOnlyCandidate() throws Exception {
        var result=binder.selectAndBind(model(Map.of("status","NO_MATCH","selections",List.of())),
            "An unrelated request",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("NO_MATCH");assertThat(result.selectedIds()).isEmpty();
    }

    @Test void invalidIdInvalidatesTheProtocolInsteadOfExecutingOtherSelections() throws Exception {
        var result=binder.selectAndBind(model(Map.of("status","SELECTED","selections",
            List.of(selection("a","C42"),selection("invented","C42")))),"C42",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("INVALID_TEMPLATE_ID");assertThat(result.protocols()).isEmpty();
    }

    @Test void assetBindingIsCheckedWithoutJudgingBusinessRelevance() throws Exception {
        var selection=new LinkedHashMap<>(selection("a","C42"));selection.put("assetId","unauthorized-asset");
        var result=binder.selectAndBind(model(Map.of("status","SELECTED","selections",List.of(selection))),
            "C42",List.of(template("a","asset-1")));
        assertThat(result.status()).isEqualTo("INVALID_ASSET_BINDING");
    }

    @Test void missingRequiredParameterProducesContractFeedback() throws Exception {
        var result=binder.selectAndBind(model(Map.of("status","SELECTED","selections",
            List.of(Map.of("templateId","a","parameters",Map.of())))),"Analyze the customer",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("PARAMETERS_REQUIRED");
        assertThat(result.selectedIds()).containsExactly("a");
        assertThat(result.protocols().get(0).get("unresolved_parameters")).isEqualTo(List.of("customer"));
    }

    @Test void unknownParameterIsAContractError() throws Exception {
        var result=binder.selectAndBind(model(Map.of("status","SELECTED","selections",
            List.of(Map.of("templateId","a","parameters",Map.of("invented","C42"))))),"C42",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("INVALID_PARAMETERS");
    }

    @Test void missingSelectionFieldIsNotAnMcpFailureOrNoMatch() throws Exception {
        var result=binder.selectAndBind(model(Map.of("status","SELECTED")),"C42",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(result.selectedIds()).isEmpty();
    }

    @Test void modelTimeoutRetainsItsOwnFailureCategory() {
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("timeout"));
        var result=binder.selectAndBind(model,"C42",List.of(template("a",null)));
        assertThat(result.status()).isEqualTo("MODEL_UNAVAILABLE");
        verify(model,times(1)).chat(any(ChatRequest.class));
    }

    @Test void discoveryWithoutCandidatesNeedsNoModelCall() {
        var model=mock(ChatModel.class);
        assertThat(binder.selectAndBind(model,"C42",List.of()).status()).isEqualTo("NO_CANDIDATES");
        verifyNoInteractions(model);
    }

    private Map<String,Object> selection(String id,String customer) {
        return Map.of("templateId",id,"parameters",Map.of("customer",customer));
    }
    private Map<String,Object> template(String id,String asset) {
        var result=new LinkedHashMap<String,Object>();result.put("templateId",id);result.put("title","Published capability "+id);
        result.put("parameterSchema",Map.of("type","object","required",List.of("customer"),
            "properties",Map.of("customer",Map.of("type","string"))));
        if(asset!=null)result.put("asset",Map.of("id",asset));return result;
    }
    private ChatModel model(Map<String,Object> decision) throws Exception {
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
            .aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("selection-1")
                .name(SchemaDrivenTemplateParameterBinder.SELECTION_FUNCTION).arguments(mapper.writeValueAsString(decision)).build())).build());
        return model;
    }
}
