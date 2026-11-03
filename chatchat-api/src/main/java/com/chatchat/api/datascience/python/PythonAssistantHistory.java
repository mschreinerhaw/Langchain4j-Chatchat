package com.chatchat.api.datascience.python;

import com.chatchat.api.workspace.WorkspaceOriginService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.conversation.model.Conversation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Text-only answers join the existing Studio history store used by Coding tasks. */
@Service @RequiredArgsConstructor
public class PythonAssistantHistory {
    private final WorkspaceOriginService origins;
    private final ObjectAccessService access;
    private final ConversationService conversations;
    @Transactional
    public void record(HttpServletRequest request,PythonCodeAssistantService.AssistRequest input,PythonCodeAssistantService.AssistResponse answer){
        var conversation=origins.createForStudio(request,"PYTHON_STUDIO",input.historyPrompt());
        var now=java.time.LocalDateTime.now();
        var user=Conversation.Message.builder().id(java.util.UUID.randomUUID().toString()).role("user").content(input.historyPrompt()).timestamp(now).build();
        var assistant=Conversation.Message.builder().id(java.util.UUID.randomUUID().toString()).role("assistant").content(answer.code())
            .timestamp(now).status("completed").streaming(false).modelName(answer.modelName()).agentName("PythonStudio").build();
        conversations.mergeMessages(access.tenant(request),conversation.getId(),access.user(request),java.util.List.of(user,assistant));
        conversations.updateConversationSummary(access.tenant(request),conversation.getId(),access.user(request),input.historyPrompt(),"completed",null,answer.modelName(),"llm_chat","PythonStudio");
    }
}
