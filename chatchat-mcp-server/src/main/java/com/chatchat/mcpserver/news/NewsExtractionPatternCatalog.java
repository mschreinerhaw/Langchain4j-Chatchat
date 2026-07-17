package com.chatchat.mcpserver.news;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Built-in URL filters shown by the MCP news administration page. */
@Component
public class NewsExtractionPatternCatalog {
    private static final List<PatternPreset> PRESETS = List.of(
        preset("all_http_pages", "全部 HTTP 页面",
            "允许域名下的全部 HTTP/HTTPS 页面，适合入口范围已经很小的站点。",
            "(?i)^https?://.+$"),
        preset("common_news_detail", "常见新闻详情页",
            "匹配路径中包含 news、article、detail、content 等常见详情标识的页面。",
            "(?i)^https?://[^?#]+/.*(?:news|article|detail|content)(?:[/_.-]|[?#]).*$"),
        preset("common_announcement", "公告与信息披露",
            "匹配公告、通知、披露和公告列表下的详情页面。",
            "(?i)^https?://[^?#]+/.*(?:notice|announcement|disclosure|bulletin)(?:[/_.-]|[?#]).*$"),
        preset("html_article", "HTML 文章页面",
            "匹配以 html、htm、shtml 或 xhtml 结尾的文章地址。",
            "(?i)^https?://[^?#]+/.*\\.(?:s?html?|xhtml)(?:[?#].*)?$"),
        preset("numeric_article", "数字编号详情页",
            "匹配路径中带有至少 6 位文章编号的常见资讯详情地址。",
            "(?i)^https?://[^?#]+/.*(?:/|_|-)\\d{6,}(?:\\.[a-z0-9]+)?(?:[?#].*)?$"),
        preset("document_attachment", "公告文档附件",
            "匹配 PDF、Word、Excel 和 CSV 公告附件。",
            "(?i)^https?://[^?#]+/.*\\.(?:pdf|docx?|xlsx?|csv)(?:[?#].*)?$")
    );

    public List<PatternPreset> presets() {
        return PRESETS;
    }

    private static PatternPreset preset(String code, String name, String description, String regex) {
        Pattern.compile(regex);
        return new PatternPreset(code, name, description, regex);
    }

    public record PatternPreset(String code, String name, String description, String regex) { }
}
