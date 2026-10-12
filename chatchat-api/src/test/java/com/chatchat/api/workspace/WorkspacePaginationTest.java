package com.chatchat.api.workspace;

import com.chatchat.chat.conversation.persistence.*;
import com.chatchat.chat.task.core.*;
import com.chatchat.chat.workspace.WorkspaceVisibility;
import com.chatchat.enterprise.workspace.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"})
@ContextConfiguration(classes = WorkspacePaginationTest.Config.class)
class WorkspacePaginationTest {
    @Configuration @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {ChatSessionEntity.class, AgentTaskLatestEntity.class, WorkspaceResourceBinding.class})
    @EnableJpaRepositories(basePackageClasses = {ChatSessionRepository.class, AgentTaskLatestRepository.class, WorkspaceResourceRepository.class})
    static class Config {}
    @Autowired ChatSessionRepository sessions;
    @Autowired AgentTaskLatestRepository tasks;
    @Autowired WorkspaceResourceRepository bindings;
    void session(String id, String tenant) {
        var row = new ChatSessionEntity(); row.setSessionId(id); row.setTenantId(tenant); row.setUserId("login-name"); row.setTitle(id); sessions.saveAndFlush(row);
    }
    void bind(String id, String type, String owner, String space) {
        var row = new WorkspaceResourceBinding(); row.setTenantId("tenant"); row.setResourceType(type); row.setResourceId(id);
        row.setOwnerId(owner); row.setOriginWorkspaceId(space); bindings.saveAndFlush(row);
    }
    @Test void privateSessionScopeIsAppliedBeforePaginationAndCount() {
        session("legacy", "tenant"); session("own-science", "tenant"); session("other-science", "tenant"); session("own-zhice", "tenant"); session("foreign", "foreign");
        bind("own-science", "session", "canonical-user", "data-science"); bind("other-science", "session", "another-user", "data-science"); bind("own-zhice", "session", "canonical-user", "zhice");
        var scope = new WorkspaceRequestContext.Scope("tenant", "canonical-user", "data-science");
        var page = sessions.findAll(WorkspaceVisibility.<ChatSessionEntity>of("session", "sessionId", scope, false), PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(1); assertThat(page.getContent()).extracting(ChatSessionEntity::getSessionId).containsExactly("own-science");
        var legacy = sessions.findAll(WorkspaceVisibility.<ChatSessionEntity>of("session", "sessionId", new WorkspaceRequestContext.Scope("tenant", "canonical-user", "zhice"), false));
        assertThat(legacy).extracting(ChatSessionEntity::getSessionId).containsExactlyInAnyOrder("legacy", "own-zhice");
    }
    @Test void taskQueryUsesCanonicalOwnerAndIgnoresCallerWorkspaceClaims() {
        for (String id : new String[]{"own", "other", "legacy"}) {
            var task = new AgentTaskLatestEntity(); task.setTaskId(id); task.setTenantId("tenant"); task.setSessionId("session"); task.setUserId("login-name"); tasks.saveAndFlush(task);
        }
        bind("own", "task", "canonical-user", "data-science"); bind("other", "task", "other-user", "data-science");
        var result = tasks.findAll(WorkspaceVisibility.<AgentTaskLatestEntity>of("task", "taskId", new WorkspaceRequestContext.Scope("tenant", "canonical-user", "data-science"), false), PageRequest.of(0, 10));
        assertThat(result.getTotalElements()).isEqualTo(1); assertThat(result.getContent()).extracting(AgentTaskLatestEntity::getTaskId).containsExactly("own");
    }
}
