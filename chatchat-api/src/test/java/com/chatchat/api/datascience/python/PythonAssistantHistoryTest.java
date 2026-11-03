package com.chatchat.api.datascience.python;

import com.chatchat.api.controller.datascience.PythonAssistantHistoryController;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class PythonAssistantHistoryTest {
    @Test void deletionChecksOwnerChannelAndLiveRuntimeBeforeRemovingHistory(){
        var conversations=mock(ConversationService.class);var access=mock(ObjectAccessService.class);var assistant=mock(PythonCodeAssistantService.class);var request=mock(HttpServletRequest.class);
        when(access.tenant(request)).thenReturn("tenant");when(access.user(request)).thenReturn("owner");
        var message=Conversation.Message.builder().id("answer").role("assistant").memoryContext(Map.of("codingTaskId","python-coding-fixture")).build();
        var conversation=Conversation.builder().id("history").userId("owner").sourceChannel("PYTHON_STUDIO").messages(List.of(message)).build();
        when(conversations.getConversation("tenant","history")).thenReturn(Optional.of(conversation));
        when(assistant.codingResult("tenant","owner","python-coding-fixture",false)).thenReturn(Map.of("status","RUNNING"));
        var controller=new PythonAssistantHistoryController(conversations,access,assistant);
        assertThatThrownBy(()->controller.delete(request,"history")).hasMessageContaining("停止");verify(conversations,never()).deleteConversation(anyString(),anyString());
        when(assistant.codingResult("tenant","owner","python-coding-fixture",false)).thenReturn(Map.of("status","COMPLETED"));
        controller.delete(request,"history");verify(conversations).deleteConversation("tenant","history");verify(access,times(2)).requireConversation(request,"history",false);
    }
    @Test void textAnswersUseExistingOwnerScopedStudioStore(){
        var origins=mock(WorkspaceOriginService.class);var access=mock(ObjectAccessService.class);var conversations=mock(ConversationService.class);var request=mock(HttpServletRequest.class);
        when(access.tenant(request)).thenReturn("tenant");when(access.user(request)).thenReturn("owner");
        when(origins.createForStudio(request,"PYTHON_STUDIO","Explain code")).thenReturn(Conversation.builder().id("saved").build());
        new PythonAssistantHistory(origins,access,conversations).record(request,new PythonCodeAssistantService.AssistRequest("explain","Explain code","print(42)","","model"),new PythonCodeAssistantService.AssistResponse("Explanation","explain",false,"model",List.of(),null));
        verify(conversations).mergeMessages(eq("tenant"),eq("saved"),eq("owner"),argThat(messages->messages.size()==2 && messages.get(0).getContent().equals("Explain code") && messages.get(1).getContent().equals("Explanation")));
    }
}
