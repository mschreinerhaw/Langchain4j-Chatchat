package com.chatchat.knowledgebase.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {

    public List<String> split(String text, int chunkSize, int overlap) {
        return splitChunks(text, chunkSize, overlap).stream()
            .map(TextChunk::content)
            .toList();
    }

    public List<TextChunk> splitChunks(String text, int chunkSize, int overlap) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        int safeChunkSize = Math.max(200, chunkSize);
        int safeOverlap = Math.max(0, Math.min(overlap, safeChunkSize / 2));
        List<TextBlock> blocks = paragraphBlocks(normalized);
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentSection = "";
        for (TextBlock block : blocks) {
            if (block.content().length() > safeChunkSize) {
                flushChunk(chunks, current, currentSection);
                chunks.addAll(splitLongBlock(block, safeChunkSize, safeOverlap));
                currentSection = "";
                continue;
            }
            if (current.length() == 0) {
                current.append(block.content());
                currentSection = block.section();
                continue;
            }
            if (current.length() + 2 + block.content().length() <= safeChunkSize) {
                current.append("\n\n").append(block.content());
                currentSection = mergeSection(currentSection, block.section());
            } else {
                flushChunk(chunks, current, currentSection);
                current.append(block.content());
                currentSection = block.section();
            }
        }
        flushChunk(chunks, current, currentSection);
        return chunks;
    }

    private List<TextBlock> paragraphBlocks(String content) {
        List<TextBlock> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = "";
        for (String rawLine : content.split("\\n")) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                flushBlock(blocks, current, currentHeading);
                continue;
            }
            if (isHeading(line)) {
                flushBlock(blocks, current, currentHeading);
                currentHeading = line.replaceAll("\\s+", " ");
                continue;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(line.replaceAll("\\s+", " "));
        }
        flushBlock(blocks, current, currentHeading);
        if (blocks.isEmpty()) {
            blocks.add(new TextBlock(content.replaceAll("\\s+", " ").trim(), ""));
        }
        return blocks;
    }

    private boolean isHeading(String line) {
        return line.startsWith("#")
            || line.matches("^\\d+(\\.\\d+)*[\\u3001.\\)]\\s+.+")
            || line.matches("^[\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u4e03\\u516b\\u4e5d\\u5341]+[\\u3001.]\\s*.+");
    }

    private void flushBlock(List<TextBlock> blocks, StringBuilder current, String heading) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString().trim();
        if (!text.isBlank()) {
            String section = heading == null ? "" : heading.trim();
            blocks.add(new TextBlock((section.isBlank() ? "" : section + "\n") + text, section));
        }
        current.setLength(0);
    }

    private void flushChunk(List<TextChunk> chunks, StringBuilder current, String section) {
        if (current.length() == 0) {
            return;
        }
        String text = current.toString().trim();
        if (!text.isBlank()) {
            chunks.add(new TextChunk(text, section == null ? "" : section));
        }
        current.setLength(0);
    }

    private List<TextChunk> splitLongBlock(TextBlock block, int chunkSize, int overlap) {
        List<TextChunk> chunks = new ArrayList<>();
        String text = block.content();
        String heading = block.section().isBlank() ? leadingHeading(text) : block.section();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String chunk = text.substring(start, end).trim();
            if (!heading.isBlank() && !chunk.startsWith(heading)) {
                chunk = heading + "\n" + chunk;
            }
            chunks.add(new TextChunk(chunk, heading));
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private String leadingHeading(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        if (newline <= 0) {
            return "";
        }
        String firstLine = text.substring(0, newline).trim();
        return isHeading(firstLine) ? firstLine : "";
    }

    private String mergeSection(String currentSection, String nextSection) {
        if (currentSection == null || currentSection.isBlank()) {
            return nextSection == null ? "" : nextSection;
        }
        if (nextSection == null || nextSection.isBlank() || currentSection.equals(nextSection)) {
            return currentSection;
        }
        return currentSection + " / " + nextSection;
    }

    public record TextChunk(String content, String section) {
    }

    private record TextBlock(String content, String section) {
    }
}
