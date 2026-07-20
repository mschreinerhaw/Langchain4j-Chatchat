package com.chatchat.mcpserver.notification;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationContentProtocolParserTest {

    private final NotificationContentProtocolParser parser = new NotificationContentProtocolParser(new ObjectMapper());

    @Test
    void validatesImmutableSourceAndRendersPresentationPlan() {
        String source = "风险日报\n系统运行正常\n无新增告警";
        Map<String, Object> resolved = parser.resolve(Map.of(
            "receiver", "ops",
            "contentProtocol", protocol(source, ModelProtocolJson.sha256Hex(source))
        ));

        assertThat(resolved).containsEntry("title", "风险日报")
            .containsEntry("content", "## 风险日报\n\n- 系统运行正常\n- 无新增告警")
            .containsEntry("sourceSha256", ModelProtocolJson.sha256Hex(source));
    }

    @Test
    void rejectsChangedFixedAnswer() {
        String source = "固定答案\n不得修改";
        Map<String, Object> changed = protocol(source, ModelProtocolJson.sha256Hex(source));
        changed.put("sourceContent", source + "。");

        assertThatThrownBy(() -> parser.resolve(Map.of("contentProtocol", changed)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("digest mismatch");
    }

    @Test
    void rejectsTitleThatWasNotExtractedFromSource() {
        String source = "固定答案\n不得修改";
        Map<String, Object> invalid = protocol(source, ModelProtocolJson.sha256Hex(source));
        invalid.put("title", "新增标题");

        assertThatThrownBy(() -> parser.resolve(Map.of("contentProtocol", invalid)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("extracted verbatim");
    }

    private Map<String, Object> protocol(String source, String digest) {
        return new java.util.LinkedHashMap<>(Map.of(
            "version", NotificationContentProtocolParser.VERSION,
            "title", "风险日报".equals(source.lines().findFirst().orElse("")) ? "风险日报" : "固定答案",
            "sourceContent", source,
            "sourceSha256", digest,
            "format", "MARKDOWN",
            "blocks", List.of(
                Map.of("type", "HEADING", "startLine", 1, "endLine", 1),
                Map.of("type", "LIST", "startLine", 2, "endLine", source.split("\\R", -1).length)
            )
        ));
    }
}
