package com.chatchat.api.controller.chat;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.task.core.AgentTaskLatestEntity;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArchivedConversationReportsTest {
    @Test
    void restoresOneReportForTheOwnedConversationAndKeepsItsFallback() {
        var assets = mock(UserAssetService.class);
        var tasks = mock(AgentTaskLatestRepository.class);
        var access = mock(ObjectAccessService.class);
        var request = mock(HttpServletRequest.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<UserAssetService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assets);
        when(access.user(request)).thenReturn("user");
        when(access.tenant(request)).thenReturn("tenant");
        when(access.ownerAliases(request)).thenReturn(Set.of("user"));
        var service = new ArchivedConversationReports(provider, tasks, access);
        var task = mock(AgentTaskLatestEntity.class);
        when(task.getTenantId()).thenReturn("tenant");
        when(task.getUserId()).thenReturn("user");
        when(task.getCanonicalState()).thenReturn("SUCCEEDED");
        when(task.getSessionId()).thenReturn("session");
        when(task.getExecutionAttemptId()).thenReturn("attempt");
        when(tasks.findById("task")).thenReturn(Optional.of(task));
        var owner = new UserAssetService.Owner("tenant", "user");
        var entry = new UserAssetService.File("html-asset", "ARTIFACT", "report.html", "text/html", "hash",
            20, "AVAILABLE", "native-task", "attempt", "coding-node:node:output/report.html", 0);
        var message = new Conversation.Message();
        message.setRole("assistant");
        message.setTaskId("task");
        Map<String, Object> fallback = Map.of("visualReport", Map.of("markdown", "Report fallback"));
        message.setUiResponse(fallback);
        var conversation = Conversation.builder().id("session").tenantId("tenant").userId("user")
            .messages(List.of(message)).build();

        when(assets.findNativeHtmlEntries(owner, "attempt")).thenReturn(List.of(entry, entry), List.of(entry));
        service.restore(request, conversation);
        assertThat(message.getUiResponse()).isEqualTo(fallback);

        when(task.getSessionId()).thenReturn("different-session");
        service.restore(request, conversation);
        verify(assets, times(1)).findNativeHtmlEntries(owner, "attempt");
        assertThat(message.getUiResponse()).isEqualTo(fallback);

        when(task.getSessionId()).thenReturn("session");
        service.restore(request, conversation);
        assertThat(message.getUiResponse().get("visualReport")).isEqualTo(Map.of(
            "schemaVersion", "python_visual_report.v1", "title", "分析报告", "markdown", "Report fallback",
            "htmlReport", Map.of("schemaVersion", "runtime_html_report.v1", "entryFileId", "html-asset",
                "entryPath", "output/report.html", "taskId", "native-task", "runId", "attempt")));
    }
}
