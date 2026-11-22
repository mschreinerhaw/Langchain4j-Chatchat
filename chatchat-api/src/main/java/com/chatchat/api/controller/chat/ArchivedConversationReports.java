package com.chatchat.api.controller.chat;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.nativecline.NativeHtmlReport;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.model.Conversation;
import com.chatchat.chat.task.core.AgentExecutionState;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.common.runtime.coding.VisualReportMode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class ArchivedConversationReports {
    private final ObjectProvider<UserAssetService> assets;
    private final AgentTaskLatestRepository tasks;
    private final ObjectAccessService access;

    Conversation restore(HttpServletRequest request, Conversation conversation) {
        UserAssetService service = assets.getIfAvailable();
        String user = access.user(request);
        String tenant = access.tenant(request);
        if (service == null || user == null || !Objects.equals(tenant, conversation.getTenantId())
            || conversation.getMessages() == null) return conversation;
        var owners = access.ownerAliases(request);
        if (!owners.contains(conversation.getUserId())) return conversation;
        var owner = new UserAssetService.Owner(tenant, user);
        for (var message : conversation.getMessages()) {
            if (!"assistant".equals(message.getRole()) || Boolean.TRUE.equals(message.getStreaming())
                || message.getTaskId() == null || message.getTaskId().isBlank()) continue;
            Map<String, Object> ui = message.getUiResponse() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(message.getUiResponse());
            var visual = new LinkedHashMap<String, Object>();
            if (ui.get("visualReport") instanceof Map<?, ?> existing) {
                if (existing.get("htmlReport") instanceof Map<?, ?>) continue;
                existing.forEach((key, value) -> { if (key instanceof String name) visual.put(name, value); });
            }
            try {
                var task = tasks.findById(message.getTaskId()).orElse(null);
                if (task == null || !tenant.equals(task.getTenantId()) || !owners.contains(task.getUserId())
                    || !conversation.getId().equals(task.getSessionId()) || task.getExecutionAttemptId() == null
                    || !AgentExecutionState.fromWire(task.getCanonicalState()).terminal()) continue;
                var entries = service.findNativeHtmlEntries(owner, task.getExecutionAttemptId());
                if (entries.size() != 1 || entries.get(0).size() > 8L * 1024 * 1024) continue;
                visual.put("htmlReport", NativeHtmlReport.descriptor(entries.get(0)));
                visual.putIfAbsent("schemaVersion", VisualReportMode.SCHEMA);
                visual.putIfAbsent("title", "分析报告");
                ui.put("visualReport", visual);
                message.setUiResponse(ui);
            } catch (DataAccessException | IllegalArgumentException | SecurityException unavailable) {
                log.debug("Archived HTML could not be restored for task {}", message.getTaskId(), unavailable);
            }
        }
        return conversation;
    }
}
