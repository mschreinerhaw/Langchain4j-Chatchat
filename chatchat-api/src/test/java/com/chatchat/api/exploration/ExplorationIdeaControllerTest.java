package com.chatchat.api.exploration;

import com.chatchat.agents.orchestration.model.AgentChatModelResolver;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ExplorationIdeaControllerTest {
    WorkspaceService workspaces=mock(WorkspaceService.class);ObjectAccessService access=mock(ObjectAccessService.class);
    ExplorationWorkspaceStore store=mock(ExplorationWorkspaceStore.class);ExplorationAgentBridge agents=mock(ExplorationAgentBridge.class);
    UserAssetService assets=mock(UserAssetService.class);AgentChatModelResolver models=mock(AgentChatModelResolver.class);
    ChatModel model=mock(ChatModel.class);ObjectMapper json=new ObjectMapper();MockHttpServletRequest http=new MockHttpServletRequest();
    UserAssetService.Owner owner=new UserAssetService.Owner("tenant","alice");
    ExplorationIdeaController controller=new ExplorationIdeaController(workspaces,access,store,agents,json,new com.chatchat.api.assistance.QuestionIdeaService(assets,models,json));
    @BeforeEach void setup(){when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("alice");when(models.resolveChatModel(null)).thenReturn(model);}
    @Test void loadsOwnedCurrentNodeAndSupplementalTextBeforeOneModelCall() throws Exception {
        var project=json.createObjectNode().put("title","七月客户分析");var node=project.putArray("nodes").addObject().put("id","node").put("question","核对资产盈亏");
        node.putArray("fileIds");node.putObject("receipt").put("resultAvailable",true).putObject("visualReport").put("summary","期末资产120元，净流入20元，盈亏口径待确认");
        when(store.get(owner,"project")).thenReturn(project);
        when(assets.get(owner,"supplement")).thenReturn(new UserAssetService.File("supplement","DOCUMENT","口径补充.txt","text/plain","hash",30,"AVAILABLE","","","upload",0));
        when(assets.read(owner,"supplement")).thenReturn("期初资产为100元，币种人民币".getBytes(StandardCharsets.UTF_8));
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ideas\":[{\"title\":\"核对资金与资产\",\"prompt\":\"结合期初100元和净流入20元，核对期末120元是否一致\"}]}")).build());
        var result=controller.ideas(http,new ExplorationIdeaController.Request("project","node","关注资金变动",null,List.of("supplement")));
        assertThat(result.getData().toString()).contains("期初100元");
        verify(model,times(1)).chat(argThat((ChatRequest request)->request.messages().toString().contains("期末资产120元")&&request.messages().toString().contains("期初资产为100元")&&request.messages().toString().contains("关注资金变动")&&request.toolSpecifications().isEmpty()));
        verify(workspaces).requireAvailable(http,"data-exploration");
    }
    @Test void deniedWorkspaceNeverLoadsContextOrCallsTheModel(){
        doThrow(new SecurityException("revoked")).when(workspaces).requireAvailable(http,"data-exploration");
        assertThatThrownBy(()->controller.ideas(http,new ExplorationIdeaController.Request(null,null,"问题",null,List.of()))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(store,assets,models,model);
    }
    @Test void foreignProjectAndForeignAttachmentNeverReachTheModel(){
        when(store.get(owner,"foreign")).thenThrow(new SecurityException("foreign project"));
        assertThatThrownBy(()->controller.ideas(http,new ExplorationIdeaController.Request("foreign","node","",null,List.of()))).isInstanceOf(SecurityException.class);
        when(assets.get(owner,"foreign-file")).thenThrow(new SecurityException("foreign file"));
        assertThatThrownBy(()->controller.ideas(http,new ExplorationIdeaController.Request(null,null,"",null,List.of("foreign-file")))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(models,model);
    }
    @Test void invalidModelOutputIsAnErrorWithoutTemplateFallback(){
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ideas\":[]}")).build());
        assertThatThrownBy(()->controller.ideas(http,new ExplorationIdeaController.Request(null,null,"核对资金",null,List.of()))).hasMessageContaining("有效提问建议");
        verify(model,times(1)).chat(any(ChatRequest.class));
    }
    @Test void timedOutModelReturnsActionableErrorAndReleasesTheSlotForRetry() throws Exception {
        when(model.chat(any(ChatRequest.class)))
            .thenThrow(new com.chatchat.agents.orchestration.model.AgentDeadlineExceededException("deadline"))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ideas\":[{\"title\":\"核对范围\",\"prompt\":\"核对本期客户资产范围\"}]} ")).build());
        var request=new ExplorationIdeaController.Request(null,null,"核对资金",null,List.of());
        assertThatThrownBy(()->controller.ideas(http,request))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("504").hasMessageContaining("重新生成");
        assertThat(controller.ideas(http,request).getData().toString()).contains("核对范围");
        verify(model,times(2)).chat(any(ChatRequest.class));
    }
}
