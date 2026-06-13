package com.chatchat.mcpserver.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class WebContentProcessor {

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "this", "that", "are", "was", "were",
        "you", "your", "not", "have", "has", "can", "will", "about", "into", "more",
        "一个", "我们", "你们", "他们", "这个", "那个", "以及", "对于", "通过", "可以"
    );

    private final WebCrawlerProperties properties;

    public WebContentProcessor(WebCrawlerProperties properties) {
        this.properties = properties;
    }

    /**
     * Extracts readable content from HTML.
     *
     * @param url the url value
     * @param html the html value
     * @return the operation result
     */
    public ProcessedContent process(String url, String html) {
        Document document = Jsoup.parse(html == null ? "" : html, url == null ? "" : url);
        document.select("script,style,noscript,template,svg,canvas,iframe,nav,header,footer,aside,form").remove();
        document.select("[aria-hidden=true],.ad,.ads,.advertisement,.cookie,.breadcrumb,.sidebar,.menu").remove();

        String title = normalize(document.title());
        Element main = first(
            document.selectFirst("article"),
            document.selectFirst("main"),
            document.selectFirst("[role=main]"),
            document.body()
        );
        String mainText = normalize(main == null ? document.text() : main.text());
        int maxTextChars = Math.max(1000, properties.getMaxTextChars());
        boolean truncated = mainText.length() > maxTextChars;
        if (truncated) {
            mainText = mainText.substring(0, maxTextChars);
        }

        List<String> chunks = chunk(mainText);
        List<String> keywords = keywords(title + " " + mainText);
        return new ProcessedContent(title, mainText, chunks, keywords, truncated);
    }

    /**
     * Converts processed content to a map.
     *
     * @param content the content value
     * @return the converted map
     */
    public Map<String, Object> toMap(ProcessedContent content) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", content.title());
        values.put("main_text", content.mainText());
        values.put("chunks", content.chunks());
        values.put("keywords", content.keywords());
        values.put("truncated", content.truncated());
        return values;
    }

    private List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int chunkChars = Math.max(300, properties.getChunkChars());
        int overlap = Math.max(0, Math.min(properties.getChunkOverlapChars(), chunkChars / 3));
        int maxChunks = Math.max(1, properties.getMaxChunks());
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            int end = Math.min(text.length(), start + chunkChars);
            if (end < text.length()) {
                int sentenceEnd = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
                if (sentenceEnd > start + chunkChars / 2) {
                    end = sentenceEnd + 1;
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private List<String> keywords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+")) {
            if (token.length() < 2 || STOP_WORDS.contains(token)) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
            .limit(12)
            .map(Map.Entry::getKey)
            .distinct()
            .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private Element first(Element... elements) {
        for (Element element : elements) {
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    public record ProcessedContent(
        String title,
        String mainText,
        List<String> chunks,
        List<String> keywords,
        boolean truncated
    ) {
    }
}
