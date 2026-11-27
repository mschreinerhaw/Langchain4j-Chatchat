package com.chatchat.api.enterprise.controller;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.exception.RuntimeScopeAccessDeniedException;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.task.core.AgentTaskLatestEntity;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.chat.task.event.AgentEvent;
import com.chatchat.chat.task.event.AgentEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportExportSourceTest {
    @Test
    void onlyTheOwnerCanRenderAnArchivedAssistantAnswerWithTheMatchingConversationId() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:report-source;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table chat_session(session_id varchar(64),tenant_id varchar(64),user_id varchar(64))");
        jdbc.update("insert into chat_session values ('conversation','tenant-a','owner')");
        var access = new ObjectAccessService(jdbc, mock(com.chatchat.enterprise.service.EnterpriseAdminService.class));
        var conversations = mock(ConversationService.class);
        @SuppressWarnings("unchecked")
        var provider = (ObjectProvider<UserAssetService>) mock(ObjectProvider.class);
        var tasks = mock(AgentTaskLatestRepository.class);
        var events = mock(AgentEventStore.class);
        var json = new ObjectMapper();
        var sources = new ReportExportSource(provider, access, conversations, tasks, events, json);
        var answer = new Conversation.Message();
        answer.setId("answer-1");
        answer.setRole("assistant");
        answer.setContent("# Browser substituted answer");
        answer.setTaskId("task");
        var prompt = new Conversation.Message();
        prompt.setId("prompt-1");
        prompt.setRole("user");
        var conversation = new Conversation();
        conversation.setMessages(List.of(answer, prompt));
        when(conversations.getConversation("tenant-a", "conversation")).thenReturn(Optional.of(conversation));
        var task = new AgentTaskLatestEntity();
        task.setTaskId("task");
        task.setTenantId("tenant-a");
        task.setUserId("owner");
        task.setSessionId("conversation");
        task.setCanonicalState("SUCCEEDED");
        task.setExecutionAttemptId("attempt");
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        when(events.findLatestByTask("tenant-a", "conversation", "task")).thenReturn(Optional.of(AgentEvent.builder()
            .type("ANSWER").attemptId("attempt")
            .payload("{\"uiResponse\":{\"answer\":\"# Verified answer\\n\\n<script>alert(1)</script>\"}}").build()));
        var reference = new ReportExportSource.Reference(null, "conversation", "answer-1");
        assertThatThrownBy(() -> sources.load(request("tenant-b", "owner"), reference))
            .isInstanceOf(RuntimeScopeAccessDeniedException.class);
        assertThatThrownBy(() -> sources.load(request("tenant-a", "another"), reference))
            .isInstanceOf(RuntimeScopeAccessDeniedException.class);
        verifyNoInteractions(conversations);
        var document = sources.load(request("tenant-a", "owner"), reference);
        assertThat(document.files()).hasSize(1);
        String html = new String(Base64.getDecoder().decode(document.files().get(0).content()), StandardCharsets.UTF_8);
        assertThat(html).contains("<h1>Verified answer</h1>", "&lt;script&gt;")
            .doesNotContain("<script>", "Browser substituted answer");
        assertThatThrownBy(() -> sources.load(request("tenant-a", "owner"),
            new ReportExportSource.Reference(null, "conversation", "prompt-1"))).isInstanceOf(ResponseStatusException.class);
    }

    private static MockHttpServletRequest request(String tenant, String owner) {
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID, tenant);
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID, owner);
        return request;
    }
}
