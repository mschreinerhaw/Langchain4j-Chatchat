package com.chatchat.mcpserver.notification;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates and renders the immutable notification content protocol produced by ChatChat API. */
@Component
@RequiredArgsConstructor
class NotificationContentProtocolParser {

    static final String VERSION = "chatchat.notification.v1";
    private static final Set<String> BLOCK_TYPES = Set.of("HEADING", "PARAGRAPH", "LIST", "QUOTE", "CODE");

    private final ObjectMapper objectMapper;

    Map<String, Object> resolve(Map<String, Object> arguments) {
        Map<String, Object> resolved = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Object rawProtocol = resolved.get("contentProtocol");
        if (rawProtocol == null) {
            return resolved;
        }
        Map<String, Object> protocol = protocolMap(rawProtocol);
        String version = requiredText(protocol.get("version"), "contentProtocol.version is required");
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported contentProtocol.version: " + version);
        }
        String source = requiredTextWithoutTrim(protocol.get("sourceContent"), "contentProtocol.sourceContent is required");
        String expectedDigest = requiredText(protocol.get("sourceSha256"), "contentProtocol.sourceSha256 is required");
        String actualDigest = ModelProtocolJson.sha256Hex(source);
        if (!actualDigest.equalsIgnoreCase(expectedDigest)) {
            throw new IllegalArgumentException("contentProtocol source digest mismatch; immutable answer was changed");
        }
        String title = requiredText(protocol.get("title"), "contentProtocol.title is required");
        if (title.length() > 120) {
            throw new IllegalArgumentException("contentProtocol.title must not exceed 120 characters");
        }
        if (!source.contains(title)) {
            throw new IllegalArgumentException("contentProtocol.title must be extracted verbatim from sourceContent");
        }
        List<String> lines = List.of(source.split("\\R", -1));
        List<Block> blocks = parseAndValidateBlocks(protocol.get("blocks"), lines.size());

        resolved.put("title", title);
        resolved.put("content", render(lines, blocks));
        resolved.put("contentProtocolVersion", version);
        resolved.put("sourceSha256", actualDigest);
        return resolved;
    }

    private Map<String, Object> protocolMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> value = new LinkedHashMap<>();
            map.forEach((key, item) -> value.put(String.valueOf(key), item));
            return value;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (Exception ex) {
                throw new IllegalArgumentException("contentProtocol must be a JSON object", ex);
            }
        }
        throw new IllegalArgumentException("contentProtocol must be an object");
    }

    private List<Block> parseAndValidateBlocks(Object raw, int lineCount) {
        if (!(raw instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalArgumentException("contentProtocol.blocks is required");
        }
        List<Block> blocks = new ArrayList<>();
        int expectedStart = 1;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("contentProtocol.blocks entries must be objects");
            }
            String type = requiredText(map.get("type"), "contentProtocol block type is required")
                .toUpperCase(Locale.ROOT);
            int start = integer(map.get("startLine"));
            int end = integer(map.get("endLine"));
            if (!BLOCK_TYPES.contains(type) || start != expectedStart || end < start || end > lineCount) {
                throw new IllegalArgumentException(
                    "contentProtocol.blocks must cover every source line once and in original order");
            }
            blocks.add(new Block(type, start, end));
            expectedStart = end + 1;
        }
        if (expectedStart != lineCount + 1) {
            throw new IllegalArgumentException("contentProtocol.blocks must include the final source line");
        }
        return blocks;
    }

    private String render(List<String> lines, List<Block> blocks) {
        List<String> rendered = new ArrayList<>();
        for (Block block : blocks) {
            List<String> sourceLines = lines.subList(block.startLine() - 1, block.endLine());
            String section = switch (block.type()) {
                case "HEADING" -> sourceLines.stream().map(line -> line.isBlank() ? line : "## " + line)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
                case "LIST" -> sourceLines.stream().map(line -> line.isBlank() || isListLine(line) ? line : "- " + line)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
                case "QUOTE" -> sourceLines.stream().map(line -> line.isBlank() ? ">" : "> " + line)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
                case "CODE" -> "```\n" + String.join("\n", sourceLines) + "\n```";
                default -> String.join("\n", sourceLines);
            };
            rendered.add(section);
        }
        return String.join("\n\n", rendered);
    }

    private boolean isListLine(String line) {
        String value = line.stripLeading();
        return value.matches("(?:[-*+]\\s+|\\d+[.)]\\s+).*?");
    }

    private int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return -1;
        }
    }

    private String requiredText(Object value, String message) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private String requiredTextWithoutTrim(Object value, String message) {
        String text = value == null ? null : String.valueOf(value);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private record Block(String type, int startLine, int endLine) {
    }
}
