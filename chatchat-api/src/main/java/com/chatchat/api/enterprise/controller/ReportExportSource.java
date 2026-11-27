package com.chatchat.api.enterprise.controller;

import com.chatchat.api.assets.UserAssetService;
import com.chatchat.api.runtime.coding.nativecline.NativeHtmlReport;
import com.chatchat.api.security.ObjectAccessService;
import com.chatchat.chat.conversation.service.ConversationService;
import com.chatchat.chat.task.core.AgentExecutionState;
import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.chatchat.chat.task.event.AgentEvent;
import com.chatchat.chat.task.event.AgentEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ReportExportSource {
    record Reference(String reportId, String conversationId, String messageId) {
        boolean isArchive() { return reportId != null; }
        String auditId() { return isArchive() ? reportId : messageId; }
    }
    record Document(String entry, List<NativeHtmlReport.Resource> files) {}

    private final ObjectProvider<UserAssetService> assets;
    private final ObjectAccessService access;
    private final ConversationService conversations;
    private final AgentTaskLatestRepository tasks;
    private final AgentEventStore events;
    private final ObjectMapper json;
    private final Parser markdownParser = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final HtmlRenderer markdownRenderer = HtmlRenderer.builder()
        .extensions(List.of(TablesExtension.create())).escapeHtml(true).sanitizeUrls(true).build();

    ReportExportSource(ObjectProvider<UserAssetService> assets, ObjectAccessService access, ConversationService conversations,
                       AgentTaskLatestRepository tasks, AgentEventStore events, ObjectMapper json) {
        this.assets = assets;
        this.access = access;
        this.conversations = conversations;
        this.tasks = tasks;
        this.events = events;
        this.json = json;
    }

    Document load(HttpServletRequest request, Reference reference) {
        if (reference.isArchive()) {
            var service = assets.getIfAvailable();
            if (service == null) throw missing();
            var owner = new UserAssetService.Owner(access.tenant(request), access.user(request));
            var bundle = NativeHtmlReport.load(service, owner, reference.reportId());
            return new Document(NativeHtmlReport.path(bundle.entry()), bundle.files());
        }
        access.requireConversation(request, reference.conversationId(), false);
        var conversation = conversations.getConversation(access.tenant(request), reference.conversationId()).orElseThrow(ReportExportSource::missing);
        if (conversation.getMessages() == null) throw missing();
        var message = conversation.getMessages().stream()
            .filter(row -> reference.messageId().equals(row.getId()) && "assistant".equals(row.getRole())
                && !Boolean.TRUE.equals(row.getStreaming())).findFirst().orElseThrow(ReportExportSource::missing);
        if (message.getTaskId() == null || message.getTaskId().isBlank()) throw missing();
        String tenant = access.tenant(request);
        var task = tasks.findById(message.getTaskId()).filter(value ->
            tenant.equals(value.getTenantId()) && reference.conversationId().equals(value.getSessionId())
            && access.ownerAliases(request).contains(value.getUserId())
            && AgentExecutionState.fromWire(value.getCanonicalState()).terminal()).orElseThrow(ReportExportSource::missing);
        String markdown = events.findLatestByTask(tenant, task.getSessionId(), task.getTaskId())
            .filter(event -> matchesAttempt(event, task.getExecutionAttemptId())).map(this::answer).orElse("");
        if (markdown.isBlank()) {
            markdown = events.findFirstByTaskAndType(tenant, task.getSessionId(), task.getTaskId(), "ANSWER")
                .filter(event -> matchesAttempt(event, task.getExecutionAttemptId())).map(this::answer).orElse("");
        }
        if (markdown == null || markdown.isBlank()) throw missing();
        if (markdown.length() > 500_000) throw new IllegalArgumentException("Report exceeds text limit");
        String html = """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><title>分析报告</title>
            <style>body{color:#233249;font:14px/1.65 sans-serif} h1,h2,h3{break-after:avoid}
            table{width:100%;border-collapse:collapse} th,td{border:1px solid #cbd5e1;padding:.35em .55em;overflow-wrap:anywhere}
            th{background:#eef3f9} tr{break-inside:avoid} pre{background:#f3f6f9;padding:8px;white-space:pre-wrap}</style>
            </head><body>""" + markdownRenderer.render(markdownParser.parse(markdown)) + "</body></html>";
        return new Document("report.html", List.of(NativeHtmlReport.Resource.of("report.html", html.getBytes(StandardCharsets.UTF_8))));
    }

    private static boolean matchesAttempt(AgentEvent event, String attempt) {
        return attempt == null || Objects.equals(attempt, event.getAttemptId());
    }

    private String answer(AgentEvent event) {
        if (!List.of("COMPLETE", "ANSWER", "RESULT").contains(event.getType()) || event.getPayload() == null) return "";
        try {
            var payload = json.readTree(event.getPayload());
            for (String pointer : List.of("/uiResponse/answer", "/executionResult/uiResponse/answer", "/answer")) {
                String value = payload.at(pointer).asText("");
                if (!value.isBlank()) return value;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new IllegalArgumentException("Stored answer has invalid JSON", malformed);
        }
        return "";
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "归档报告不存在或不可访问");
    }
}
