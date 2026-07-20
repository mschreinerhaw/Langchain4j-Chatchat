package com.chatchat.chat.task;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationContentFormatterTest {

    @Test
    void modelCanOnlyChooseExtractedTitleAndOrderedLinePresentation() {
        String fixedAnswer = "风险日报\n系统运行正常\n无新增告警";
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"title":"风险日报","blocks":[
              {"type":"HEADING","startLine":1,"endLine":1},
              {"type":"LIST","startLine":2,"endLine":3}
            ]}
            """);
        ObjectProvider<ChatModel> provider = provider(model);

        Map<String, Object> protocol = new NotificationContentFormatter(provider, new ObjectMapper()).format(fixedAnswer);

        assertThat(protocol).containsEntry("version", NotificationContentFormatter.PROTOCOL_VERSION)
            .containsEntry("title", "风险日报")
            .containsEntry("sourceContent", fixedAnswer)
            .containsEntry("sourceSha256", ModelProtocolJson.sha256Hex(fixedAnswer));
        assertThat((List<?>) protocol.get("blocks")).hasSize(2);
    }

    @Test
    void rejectsModelRewriteAndIncompleteLinePlan() {
        String fixedAnswer = "固定标题\n第一条\n第二条";
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"title":"模型改写的标题","blocks":[{"type":"LIST","startLine":2,"endLine":3}]}
            """);

        Map<String, Object> protocol = new NotificationContentFormatter(provider(model), new ObjectMapper())
            .format(fixedAnswer);

        assertThat(protocol).containsEntry("title", "固定标题").containsEntry("sourceContent", fixedAnswer);
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) protocol.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "PARAGRAPH")
            .containsEntry("startLine", 1).containsEntry("endLine", 3);
    }

    @Test
    void smsTitleIsOnlyAShortVerbatimExtraction() {
        String fixedAnswer = "这是一个长度超过短信标题限制的固定答案标题，用于验证短信仅发送短简报而不是完整答案正文\n完整正文内容";
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {"title":"这是一个长度超过短信标题限制的固定答案标题，用于验证短信仅发送短简报而不是完整答案正文","blocks":[{"type":"PARAGRAPH","startLine":1,"endLine":2}]}
            """);

        String title = new NotificationContentFormatter(provider(model), new ObjectMapper())
            .extractTitle(fixedAnswer, 20);

        assertThat(title).hasSize(20);
        assertThat(fixedAnswer).startsWith(title);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }
}
