package com.chatchat.api.security;

import com.chatchat.agents.runtime.AgentRuntime;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;

/** Runs before controller catches, including SSE and destructive object operations. */
@Component
@RequiredArgsConstructor
public class ObjectAccessInterceptor implements HandlerInterceptor {
    private final ObjectAccessService access;
    private final AgentRuntime runtime;
    private final com.chatchat.api.workspace.WorkspaceOriginService origins;
    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (access.user(request) == null) return true; // Authentication filter owns anonymous rejection.
        var variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) variables = Map.of();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v1/interactions") || path.startsWith("/api/v1/conversations")
            || path.startsWith("/api/v1/data/history") || path.startsWith("/api/v1/agent/tasks")
            || path.startsWith("/api/v1/ui-artifacts") || path.startsWith("/api/v1/search"))
            origins.requireBusinessHeader(request);
        if ((path.equals("/api/v1/agent/runtime/snapshot") || path.equals("/api/v1/agent/runtime/quality"))
            && !access.canManageTasks(request))
            throw new SecurityException("Tenant-wide runtime data requires the existing task management permission");
        if (path.startsWith("/api/v1/conversations")) {
            access.requireUser(request, variables.get("userId"));
            origins.requireBusinessOrigin(request, "CONVERSATION", variables.get("conversationId"));
        }
        if (path.startsWith("/api/v1/data/history")) {
            access.requireUser(request, variables.get("userId"));
            origins.requireBusinessOrigin(request, "CONVERSATION", variables.get("historyId"));
        }
        if (path.startsWith("/api/v1/ui-artifacts") && variables.containsKey("artifactId"))
            origins.requireBusinessOrigin(request, "UI_ARTIFACT", variables.get("artifactId"));
        if (path.startsWith("/api/v1/agent/tasks")) {
            origins.requireBusinessOrigin(request, "TASK", variables.get("taskId"));
            if (variables.containsKey("todoId")) access.requireTodo(request, variables.get("todoId"));
            origins.requireBusinessOrigin(request, "CONVERSATION", request.getParameter("sessionId"));
            if (SetHolder.ADMIN_SUMMARIES.contains(path) && !access.canManageTasks(request))
                throw new SecurityException("Tenant-wide task data requires the existing task management permission");
        }
        if (path.startsWith("/api/v1/agent/runtime/runs")) {
            access.requireUser(request, request.getParameter("userId"));
            access.requireConversation(request, request.getParameter("conversationId"), false);
            if (variables.containsKey("runId")) runtime.find(variables.get("runId")).ifPresent(run -> {
                if (run.request() == null) throw new SecurityException("Run has no authenticated owner");
                access.requireOwner(request, run.request().getTenantId(), run.request().getUserId(), true);
            });
        }
        return true;
    }
    private static class SetHolder {
        static final java.util.Set<String> ADMIN_SUMMARIES = java.util.Set.of(
            "/api/v1/agent/tasks/runtime", "/api/v1/agent/tasks/runtime/tool-audits",
            "/api/v1/agent/tasks/runtime/effects", "/api/v1/agent/tasks/runtime/experiences");
    }
}
