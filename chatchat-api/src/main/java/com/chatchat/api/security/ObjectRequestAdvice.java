package com.chatchat.api.security;

import com.chatchat.api.controller.chat.ConversationController;
import com.chatchat.api.controller.datascience.DataQueryController;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.lang.reflect.Type;

/** Protects session reuse before execution, independent of the selected application. */
@ControllerAdvice
@RequiredArgsConstructor
public class ObjectRequestAdvice extends RequestBodyAdviceAdapter {
    private final ObjectAccessService access;
    private final com.chatchat.api.workspace.WorkspaceOriginService origins;
    @Override
    public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return type == InteractionRequest.class || type == AgentTaskSubmitRequest.class
            || type == ConversationController.CreateConversationRequest.class
            || type == DataQueryController.HistoryRequest.class || type == DataQueryController.HistoryStatusRequest.class;
    }
    @Override
    public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter, Type type,
                                Class<? extends HttpMessageConverter<?>> converter) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return body;
        var request = servlet.getRequest();
        if (access.user(request) == null) return body;
        if (body instanceof DataQueryController.HistoryStatusRequest status) {
            access.requireConversation(request, status.getConversationId(), false);
            status.setTenantId(access.tenant(request)); return body;
        }
        if (body instanceof DataQueryController.HistoryRequest history) {
            access.requireUser(request, history.getUserId());
            String id = history.getConversationId() == null || history.getConversationId().isBlank()
                ? history.getHistoryId() : history.getConversationId();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("conversationId is required");
            access.requireConversation(request, id, true);
            history.setConversationId(origins.prepare(request, id));
            history.setTenantId(access.tenant(request));
            if (history.getUserId() == null) history.setUserId(access.user(request));
            return body;
        }
        if (body instanceof ConversationController.CreateConversationRequest create) {
            access.requireUser(request, create.userId());
            return new ConversationController.CreateConversationRequest(access.tenant(request),
                create.userId() == null ? access.user(request) : create.userId(), create.title());
        }
        String session = body instanceof InteractionRequest interaction ? interaction.getConversationId()
            : ((AgentTaskSubmitRequest) body).getSessionId();
        access.requireConversation(request, session, true);
        if (body instanceof InteractionRequest interaction) {
            interaction.setTenantId(access.tenant(request)); interaction.setUserId(access.user(request));
        } else {
            var task = (AgentTaskSubmitRequest) body;
            access.requireTaskSubmission(request, task.getResumeTaskId(), task.getIdempotencyKey());
            ((AgentTaskSubmitRequest) body).setTenantId(access.tenant(request));
            ((AgentTaskSubmitRequest) body).setUserId(access.user(request));
        }
        // Validate again after creation to close the check/create race on a client-supplied ID.
        session = origins.prepare(request, session);
        if (body instanceof InteractionRequest interaction) interaction.setConversationId(session);
        else ((AgentTaskSubmitRequest) body).setSessionId(session);
        return body;
    }
}
