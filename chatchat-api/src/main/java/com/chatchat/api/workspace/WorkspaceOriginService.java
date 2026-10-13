package com.chatchat.api.workspace;

import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.enterprise.entity.workspace.WorkspaceResourceOrigin;
import com.chatchat.enterprise.repository.workspace.WorkspaceResourceOriginRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Immutable creation provenance. Object access remains the central server policy. */
@Service @RequiredArgsConstructor
public class WorkspaceOriginService {
    public static final String HEADER = "X-Workspace-Id";
    private final WorkspaceService workspaces;
    private final WorkspaceResourceOriginRepository origins;
    private final ConversationService conversations;
    private final ObjectAccessService access;
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public Conversation create(HttpServletRequest request, String tenant, String user, String title) {
        String source = source(request);
        var conversation = conversations.createConversation(tenant, user, title);
        record(request, conversation.getId(), source);
        return conversation;
    }
    /** Application-owned creation provenance; the caller declares its registered workspace,
     * while server identity and policy still decide authorization. Existing clients keep create(). */
    @Transactional
    public Conversation createForWorkspace(HttpServletRequest request, String workspaceId, String title) {
        workspaces.requireAvailable(request, workspaceId);
        var conversation = conversations.createConversation(access.tenant(request), access.user(request), title);
        record(request, conversation.getId(), workspaceId);
        return conversation;
    }
    @Transactional
    public String prepare(HttpServletRequest request, String sessionId) {
        boolean existing = access.conversationExists(sessionId);
        access.requireConversation(request, sessionId, true);
        // Existing objects are authorized by their persisted owner, independent of a stale/forged UI source.
        String source = existing ? null : source(request);
        String session = conversations.ensureConversationId(access.tenant(request), sessionId, access.user(request));
        entityManager.flush(); // Make the persisted owner visible to the central JDBC policy in this transaction.
        access.requireConversation(request, session, false);
        if (!existing) record(request, session, source);
        return session;
    }
    private String source(HttpServletRequest request) {
        if (access.user(request) == null) return null;
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) return null; // Old callers remain compatible, without fabricated provenance.
        workspaces.requireAvailable(request, id);
        return id;
    }
    private void record(HttpServletRequest request, String session, String source) {
        if (source == null) return;
        var row = new WorkspaceResourceOrigin();
        row.setId(UUID.nameUUIDFromBytes((access.tenant(request) + ":CONVERSATION:" + session)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        row.setTenantId(access.tenant(request)); row.setResourceType("CONVERSATION"); row.setResourceId(session);
        row.setWorkspaceId(source); row.setCreatorUserId(access.user(request)); origins.saveAndFlush(row);
    }
    @Transactional(readOnly = true)
    public Optional<WorkspaceResourceOrigin> context(HttpServletRequest request, String type, String id) {
        String session;
        switch (type) {
            case "CONVERSATION" -> { access.requireConversation(request, id, false); session = id; }
            case "TASK" -> {
                access.requireTask(request, id);
                session = jdbc.queryForObject("select session_id from agent_task_latest where task_id = ? and tenant_id = ?",
                    String.class, id, access.tenant(request));
            }
            case "UI_ARTIFACT" -> {
                access.requireArtifact(request, id);
                session = jdbc.queryForObject("""
                    select t.session_id from ui_artifact a join agent_task_latest t
                    on a.task_id = t.task_id and a.tenant_id = t.tenant_id
                    where a.artifact_id = ? and a.tenant_id = ?
                    """, String.class, id, access.tenant(request));
            }
            default -> throw new IllegalArgumentException("Unsupported context resource type");
        }
        return session == null ? Optional.empty() : origins.findByTenantIdAndResourceTypeAndResourceId(
            access.tenant(request), "CONVERSATION", session);
    }
}
