package com.chatchat.chat.task;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds a presentation plan around an immutable Agent answer. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationContentFormatter {

    public static final String PROTOCOL_VERSION = "chatchat.notification.v1";
    private static final Set<String> BLOCK_TYPES = Set.of("HEADING", "PARAGRAPH", "LIST", "QUOTE", "CODE");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    /**
     * The model may only select a title already present in the answer and classify line ranges.
     * The immutable source text and its digest are copied by Java, never accepted from the model.
     */
    public Map<String, Object> format(String fixedAnswer) {
        String source = fixedAnswer == null ? "" : fixedAnswer;
        int lineCount = Math.max(1, source.split("\\R", -1).length);
        FormatDecision decision = modelDecision(source, lineCount);
        String title = extractedTitle(source, decision == null ? null : decision.title());
        List<Map<String, Object>> blocks = validBlocks(decision == null ? null : decision.blocks(), lineCount);

        Map<String, Object> protocol = new LinkedHashMap<>();
        protocol.put("version", PROTOCOL_VERSION);
        protocol.put("title", title);
        protocol.put("sourceContent", source);
        protocol.put("sourceSha256", ModelProtocolJson.sha256Hex(source));
        protocol.put("format", "MARKDOWN");
        protocol.put("blocks", blocks);
        return protocol;
    }

    /** Extracts a short verbatim title for SMS without asking the model to summarize the answer. */
    public String extractTitle(String fixedAnswer, int maxLength) {
        String source = fixedAnswer == null ? "" : fixedAnswer;
        int lineCount = Math.max(1, source.split("\\R", -1).length);
        FormatDecision decision = modelDecision(source, lineCount);
        String title = extractedTitle(source, decision == null ? null : decision.title());
        return title.substring(0, Math.min(Math.max(1, maxLength), title.length()));
    }

    private FormatDecision modelDecision(String source, int lineCount) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null || source.isBlank()) {
            return null;
        }
        String prompt = """
            你是通知内容排版器。以下答案已经最终确定，严禁改写、补充、删减、纠错或重新排序任何文字。
            你只能做两件事：
            1. 从原文中逐字提取一个标题；title 必须是原文的连续子串。
            2. 按原始行号把全部行连续分组，type 只能是 HEADING、PARAGRAPH、LIST、QUOTE、CODE。
            blocks 必须从第 1 行开始，到最后一行结束，无遗漏、无重叠、顺序不变。
            只返回 JSON：{"title":"原文中的标题","blocks":[{"type":"PARAGRAPH","startLine":1,"endLine":1}]}
            不要在 JSON 中复制或输出原文内容。

            lineCount: %d
            immutableAnswerJson: "%s"
            """.formatted(lineCount, ModelProtocolJson.jsonStringContent(source));
        try {
            String raw = model.chat(prompt);
            Map<String, Object> value = objectMapper.readValue(jsonObject(raw), new TypeReference<>() {});
            return new FormatDecision(text(value.get("title")), readBlocks(value.get("blocks")));
        } catch (Exception ex) {
            log.warn("Notification content model formatting failed; using immutable fallback: {}", ex.getMessage());
            return null;
        }
    }

    private List<Block> readBlocks(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Block> blocks = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                return List.of();
            }
            blocks.add(new Block(text(map.get("type")), integer(map.get("startLine")), integer(map.get("endLine"))));
        }
        return blocks;
    }

    private List<Map<String, Object>> validBlocks(List<Block> proposed, int lineCount) {
        List<Map<String, Object>> result = new ArrayList<>();
        int expectedStart = 1;
        if (proposed != null) {
            for (Block block : proposed) {
                String type = block.type() == null ? "" : block.type().trim().toUpperCase(Locale.ROOT);
                if (!BLOCK_TYPES.contains(type) || block.startLine() != expectedStart
                    || block.endLine() < block.startLine() || block.endLine() > lineCount) {
                    return fallbackBlocks(lineCount);
                }
                result.add(blockMap(type, block.startLine(), block.endLine()));
                expectedStart = block.endLine() + 1;
            }
        }
        return !result.isEmpty() && expectedStart == lineCount + 1 ? result : fallbackBlocks(lineCount);
    }

    private List<Map<String, Object>> fallbackBlocks(int lineCount) {
        return List.of(blockMap("PARAGRAPH", 1, Math.max(1, lineCount)));
    }

    private Map<String, Object> blockMap(String type, int startLine, int endLine) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("startLine", startLine);
        value.put("endLine", endLine);
        return value;
    }

    private String extractedTitle(String source, String proposed) {
        if (proposed != null && !proposed.isBlank() && proposed.trim().length() <= 120
            && source.contains(proposed.trim())) {
            return proposed.trim();
        }
        return source.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst()
            .map(line -> line.substring(0, Math.min(120, line.length())))
            .orElse("Agent 任务通知");
    }

    private String jsonObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("model output does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private record FormatDecision(String title, List<Block> blocks) {
    }

    private record Block(String type, int startLine, int endLine) {
    }
}
