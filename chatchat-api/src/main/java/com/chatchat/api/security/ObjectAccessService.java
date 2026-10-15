package com.chatchat.api.security;

import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** One server-owned object policy for every application surface. No client workspace grants. */
@Service
@RequiredArgsConstructor
public class ObjectAccessService {
    private final JdbcTemplate jdbc;
    private final EnterpriseAdminService identities;

    public String user(HttpServletRequest request) { return attribute(request, ApiAuthenticationFilter.CURRENT_USER_ID); }
    public String tenant(HttpServletRequest request) { return attribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID); }
    public Set<String> ownerAliases(HttpServletRequest request) {
        Set<String> aliases = new LinkedHashSet<>();
        if (user(request) != null) aliases.add(user(request));
        String username = attribute(request, ApiAuthenticationFilter.CURRENT_USERNAME);
        // Legacy username owners must never collide with another principal's stable ID.
        if (username != null && (username.equals(user(request)) || jdbc.queryForList(
            "select id from sys_user where id = ?", username).isEmpty())) aliases.add(username);
        return aliases;
    }
    public boolean canManageTasks(HttpServletRequest request) {
        return user(request) != null && identities.hasPermission(user(request), "platform:tasks:all");
    }
    public void requireUser(HttpServletRequest request, String requested) {
        if (requested != null && !ownerAliases(request).contains(requested)) denied();
    }
    public void requireOwner(HttpServletRequest request, String objectTenant, String owner, boolean task) {
        if (user(request) == null || tenant(request) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated identity required");
        if (!tenant(request).equals(objectTenant)) denied();
        if (!ownerAliases(request).contains(owner) && !(task && canManageTasks(request))) denied();
    }
    public void requireConversation(HttpServletRequest request, String id, boolean allowNew) {
        if (id == null || id.isBlank()) return;
        var rows = jdbc.queryForList("select tenant_id, user_id from chat_session where session_id = ?", id);
        if (rows.isEmpty()) { if (allowNew) return; missing(); }
        var row = rows.get(0);
        requireOwner(request, text(row, "tenant_id"), text(row, "user_id"), false);
    }
    public void requireTask(HttpServletRequest request, String id) {
        var rows = jdbc.queryForList("select tenant_id, user_id from agent_task_latest where task_id = ?", id);
        if (rows.isEmpty()) missing();
        var row = rows.get(0);
        requireOwner(request, text(row, "tenant_id"), text(row, "user_id"), true);
    }
    public Optional<String> requireTaskSubmission(HttpServletRequest request, String resumeId, String idempotencyKey) {
        Optional<String> session = Optional.empty();
        if (resumeId != null && !resumeId.isBlank()) {
            requireTask(request, resumeId);
            var sessions=jdbc.queryForList("select session_id from agent_task_latest where task_id = ?",resumeId);
            if(!sessions.isEmpty())session=Optional.ofNullable(text(sessions.get(0),"session_id"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) return session;
        for (var row : jdbc.queryForList("select tenant_id, user_id, session_id from agent_task_latest where tenant_id = ? and idempotency_key = ?",
            tenant(request), idempotencyKey.trim())) {
            requireOwner(request, text(row, "tenant_id"), text(row, "user_id"), false);
            if(session.isEmpty())session=Optional.ofNullable(text(row,"session_id"));
        }
        return session;
    }
    public void requireTodo(HttpServletRequest request, String id) {
        var rows = jdbc.queryForList("select tenant_id, user_id from todo_task where id = ?", id);
        if (rows.isEmpty()) missing();
        var row = rows.get(0);
        requireOwner(request, text(row, "tenant_id"), text(row, "user_id"), true);
    }
    public boolean conversationExists(String id) {
        return id != null && !id.isBlank() && !jdbc.queryForList("select session_id from chat_session where session_id = ?", id).isEmpty();
    }
    public void requireArtifact(HttpServletRequest request, String id) {
        // Ownership is derived from the persisted task, never from a supplied workspace/header.
        var rows = jdbc.queryForList("""
            select a.tenant_id, t.user_id from ui_artifact a
            join agent_task_latest t on t.task_id = a.task_id and t.tenant_id = a.tenant_id
            where a.artifact_id = ?
            """, id);
        if (rows.isEmpty()) missing(); // Unattributed legacy blobs remain stored, but are not public.
        var row = rows.get(0);
        requireOwner(request, text(row, "tenant_id"), text(row, "user_id"), true);
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? null : value.toString();
    }
    private static void denied() { throw new RuntimeScopeAccessDeniedException("Object is outside the authenticated user's scope"); }
    private static void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Object not found"); }
    private static String attribute(HttpServletRequest request, String key) {
        Object value = request == null ? null : request.getAttribute(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
