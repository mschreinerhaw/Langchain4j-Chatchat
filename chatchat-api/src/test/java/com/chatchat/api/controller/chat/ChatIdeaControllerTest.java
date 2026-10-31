package com.chatchat.api.controller.chat;

import com.chatchat.api.assistance.QuestionIdeaService;
import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.skills.catalog.SkillCatalogService;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatIdeaControllerTest {
    WorkspaceService workspaces=mock(WorkspaceService.class);ObjectAccessService access=mock(ObjectAccessService.class);
    ConversationService conversations=mock(ConversationService.class);SkillCatalogService catalog=mock(SkillCatalogService.class);
    EnterpriseAdminService identities=mock(EnterpriseAdminService.class);QuestionIdeaService ideas=mock(QuestionIdeaService.class);
    ChatIdeaController controller=new ChatIdeaController(workspaces,access,conversations,catalog,identities,ideas);
    MockHttpServletRequest http=new MockHttpServletRequest();UserAssetService.Owner owner=new UserAssetService.Owner("tenant","alice");
    @BeforeEach void setup(){when(access.tenant(http)).thenReturn("tenant");when(access.user(http)).thenReturn("alice");}
    Conversation.Message message(String role,String content){var m=new Conversation.Message();m.setRole(role);m.setContent(content);return m;}
    Conversation conversation(String channel){var c=new Conversation();c.setTenantId("tenant");c.setUserId("alice");c.setSourceChannel(channel);c.setTitle("登录超时排查");return c;}
    @Test void usesOwnedServerHistoryAndSkipsReasoningAndUnfinishedMessages() throws Exception {
        var c=conversation("PLATFORM_UI");var unfinished=message("assistant","unfinished-private");unfinished.setStreaming(true);
        c.setMessages(List.of(message("system","private-system"),message("user","09:30开始登录超时"),
            message("assistant","<think>private-reasoning</think>请确认影响范围"),unfinished));
        when(conversations.getConversation("tenant","owned")).thenReturn(Optional.of(c));
        when(ideas.generate(eq(owner),isNull(),anyMap(),eq(List.of("file")))).thenReturn(Map.of("ideas",List.of()));
        controller.generate(http,new ChatIdeaController.Request("owned","补充影响范围",null,List.of("file")));
        verify(access).requireConversation(http,"owned",false);verify(access).requireOwner(http,"tenant","alice",false);
        verify(ideas).generate(eq(owner),isNull(),argThat(context->context.toString().contains("09:30开始登录超时")&&context.toString().contains("请确认影响范围")
            &&!context.toString().contains("private")&&context.get("draft").equals("补充影响范围")),eq(List.of("file")));
    }
    @Test void newChatCanGenerateWithoutCreatingAConversation() throws Exception {
        controller.generate(http,new ChatIdeaController.Request(null,"服务器连接重置",null,List.of()));
        verifyNoInteractions(conversations,catalog,identities);verify(ideas).generate(eq(owner),isNull(),anyMap(),eq(List.of()));
    }
    @Test void rejectsUnavailableZhiceWorkspaceBeforeReadingContext(){
        doThrow(new SecurityException("revoked")).when(workspaces).requireAvailable(http,"zhice");
        assertThatThrownBy(()->controller.generate(http,new ChatIdeaController.Request(null,"draft",null,List.of()))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(conversations,ideas);
    }
    @Test void rejectsForeignConversationBeforeLoadingMessages(){
        doThrow(new SecurityException("foreign")).when(access).requireConversation(http,"foreign",false);
        assertThatThrownBy(()->controller.generate(http,new ChatIdeaController.Request("foreign","draft",null,List.of()))).isInstanceOf(SecurityException.class);
        verifyNoInteractions(conversations,ideas);
    }
    @Test void rejectsStudioHistoryInsteadOfLeakingItIntoZhice(){
        when(conversations.getConversation("tenant","studio")).thenReturn(Optional.of(conversation("SQL_STUDIO")));
        assertThatThrownBy(()->controller.generate(http,new ChatIdeaController.Request("studio","draft",null,List.of()))).hasMessageContaining("403");verifyNoInteractions(ideas);
    }
    @Test void revalidatesAgentAccessAndUsesItsCurrentModel() throws Exception {
        var agent=mock(SkillDefinition.class);when(catalog.resolve("agent")).thenReturn(agent);when(agent.id()).thenReturn("agent");when(agent.marketStatus()).thenReturn("published");when(agent.label()).thenReturn("运维助手");when(agent.modelName()).thenReturn("agent-model");
        assertThatThrownBy(()->controller.generate(http,new ChatIdeaController.Request(null,"draft","agent",List.of()))).hasMessageContaining("403");verifyNoInteractions(ideas);
        when(identities.canAccessAgent("alice","agent")).thenReturn(true);
        controller.generate(http,new ChatIdeaController.Request(null,"draft","agent",List.of()));
        verify(ideas).generate(eq(owner),eq("agent-model"),argThat(context->context.get("agent").equals("运维助手")),eq(List.of()));
    }
}
