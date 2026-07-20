package com.chatchat.mcpserver.notification;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically renders immutable notification content for each delivery channel. */
@Component
class NotificationChannelContentRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
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
            Node document = parser.parse(markdown);
            rendered.put("contentPlain", plainText(markdown));
            rendered.put("contentHtml", emailDocument(inlineStyles(htmlRenderer.render(document))));
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
}
