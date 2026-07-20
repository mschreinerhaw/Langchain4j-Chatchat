package com.chatchat.mcpserver.notification;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically renders immutable notification content for each delivery channel. */
@Component
class NotificationChannelContentRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Pattern WEB_CITATION_MARKER = Pattern.compile("(?:【|\\[)\\s*网页\\s*\\d+\\s*(?:】|])");
    private static final Pattern TOOL_EVIDENCE_SECTION = Pattern.compile(
        "(?ms)^\\s{0,3}#{1,6}\\s*工具执行证据\\s*$.*\\z");
    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s<>\\]）)}]+", Pattern.CASE_INSENSITIVE);
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
        .extensions(EXTENSIONS)
        .escapeHtml(true)
        .sanitizeUrls(true)
        .build();

    Map<String, Object> render(NotificationChannel channel, Map<String, Object> arguments) {
        Map<String, Object> rendered = new LinkedHashMap<>(arguments);
        String markdown = text(arguments.get("content"));
        if (channel == NotificationChannel.EMAIL) {
            String emailMarkdown = emailBody(markdown);
            List<Reference> references = references(arguments.get("references"));
            Node document = parser.parse(emailMarkdown);
            rendered.put("contentPlain", plainText(emailMarkdown) + plainReferences(references));
            rendered.put("contentHtml", emailDocument(
                inlineStyles(htmlRenderer.render(document)) + htmlReferences(references)));
            rendered.put("contentFormat", "HTML_WITH_PLAIN_TEXT_FALLBACK");
        } else if (channel == NotificationChannel.DINGTALK) {
            rendered.put("content", markdown);
            rendered.put("contentFormat", "DINGTALK_MARKDOWN");
        } else if (channel == NotificationChannel.WECHAT_WORK) {
            rendered.put("content", markdown);
            rendered.put("contentFormat", "WECOM_MARKDOWN");
        }
        return rendered;
    }

    private String emailBody(String markdown) {
        String withoutEvidence = TOOL_EVIDENCE_SECTION.matcher(markdown == null ? "" : markdown).replaceFirst("");
        return WEB_CITATION_MARKER.matcher(withoutEvidence).replaceAll("").trim();
    }

    private List<Reference> references(Object value) {
        if (!(value instanceof List<?> rows)) {
            return List.of();
        }
        List<Reference> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String url = referenceUrl(map);
            if (url == null || !seen.add(url)) {
                continue;
            }
            String title = firstText(map, "title", "name", "sourceName");
            if (title == null || title.equals(url)) {
                title = host(url);
            }
            String description = firstText(map, "text", "snippet", "summary", "description", "content");
            result.add(new Reference(title, url, compact(description, 240)));
        }
        return List.copyOf(result);
    }

    private String referenceUrl(Map<?, ?> map) {
        String[] keys = {"url", "href", "sourceUrl", "source_url", "link", "sourceRef", "source"};
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) {
                continue;
            }
            Matcher matcher = HTTP_URL.matcher(String.valueOf(value));
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    private String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? "引用网页" : host;
        } catch (Exception ignored) {
            return "引用网页";
        }
    }

    private String compact(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    private String htmlReferences(List<Reference> references) {
        if (references.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder(
            "<section style=\"margin-top:28px;padding-top:18px;border-top:1px solid #e5e7eb;\">"
                + "<h2 style=\"margin:0 0 12px;color:#1f2937;font-size:18px;line-height:1.4;\">引用来源</h2>"
                + "<ol style=\"margin:0;padding-left:22px;\">");
        for (Reference reference : references) {
            html.append("<li style=\"margin:10px 0;\"><strong style=\"color:#111827;\">")
                .append(escapeHtml(reference.title())).append("</strong><br><a style=\"color:#2563eb;text-decoration:none;word-break:break-all;\" href=\"")
                .append(escapeHtml(reference.url())).append("\">").append(escapeHtml(reference.url())).append("</a>");
            if (!reference.description().isBlank()) {
                html.append("<div style=\"margin-top:3px;color:#6b7280;font-size:13px;line-height:1.6;\">")
                    .append(escapeHtml(reference.description())).append("</div>");
            }
            html.append("</li>");
        }
        return html.append("</ol></section>").toString();
    }

    private String plainReferences(List<Reference> references) {
        if (references.isEmpty()) {
            return "";
        }
        StringBuilder plain = new StringBuilder("\n\n引用来源\n");
        for (int index = 0; index < references.size(); index++) {
            Reference reference = references.get(index);
            plain.append(index + 1).append(". ").append(reference.title()).append(" - ").append(reference.url());
            if (!reference.description().isBlank()) {
                plain.append("\n   ").append(reference.description());
            }
            plain.append('\n');
        }
        return plain.toString();
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String emailDocument(String body) {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <body style="margin:0;padding:24px;background:#f5f7fa;">
              <div style="max-width:760px;margin:0 auto;padding:28px;background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;font-family:Arial,'Microsoft YaHei',sans-serif;font-size:15px;line-height:1.75;color:#1f2937;">
                %s
              </div>
            </body>
            </html>
            """.formatted(body);
    }

    private String inlineStyles(String html) {
        return html
            .replace("<h1>", "<h1 style=\"margin:0 0 18px;color:#111827;font-size:26px;line-height:1.35;\">")
            .replace("<h2>", "<h2 style=\"margin:26px 0 12px;padding-bottom:7px;border-bottom:1px solid #e5e7eb;color:#1f2937;font-size:21px;line-height:1.4;\">")
            .replace("<h3>", "<h3 style=\"margin:22px 0 10px;color:#1f2937;font-size:18px;line-height:1.45;\">")
            .replace("<h4>", "<h4 style=\"margin:18px 0 8px;color:#374151;font-size:16px;line-height:1.5;\">")
            .replace("<p>", "<p style=\"margin:0 0 14px;\">")
            .replace("<ul>", "<ul style=\"margin:0 0 16px;padding-left:24px;\">")
            .replace("<ol>", "<ol style=\"margin:0 0 16px;padding-left:24px;\">")
            .replace("<li>", "<li style=\"margin:5px 0;\">")
            .replace("<blockquote>", "<blockquote style=\"margin:16px 0;padding:10px 14px;border-left:4px solid #3b82f6;background:#eff6ff;color:#374151;\">")
            .replace("<table>", "<table style=\"width:100%;margin:16px 0;border-collapse:collapse;font-size:14px;\">")
            .replace("<th>", "<th style=\"padding:9px 11px;border:1px solid #d1d5db;background:#f3f4f6;text-align:left;font-weight:700;\">")
            .replace("<td>", "<td style=\"padding:9px 11px;border:1px solid #d1d5db;vertical-align:top;\">")
            .replace("<pre>", "<pre style=\"margin:16px 0;padding:14px;overflow:auto;border-radius:6px;background:#111827;color:#f9fafb;font-size:13px;line-height:1.6;\">")
            .replace("<code>", "<code style=\"font-family:Consolas,Monaco,monospace;\">")
            .replace("<a href=", "<a style=\"color:#2563eb;text-decoration:none;\" href=")
            .replace("<strong>", "<strong style=\"font-weight:700;color:#111827;\">");
    }

    private String plainText(String markdown) {
        return markdown
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s+", "")
            .replaceAll("(?m)^\\s*>\\s?", "")
            .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
            .replaceAll("(?m)^\\s*```[^\\r\\n]*$", "")
            .replaceAll("!\\[([^]]*)]\\(([^)]+)\\)", "$1 ($2)")
            .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1 ($2)")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Reference(String title, String url, String description) {
    }
}
