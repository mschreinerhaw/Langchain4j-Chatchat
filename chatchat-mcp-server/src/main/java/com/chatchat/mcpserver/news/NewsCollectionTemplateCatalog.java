package com.chatchat.mcpserver.news;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Reusable collection workflows that are independent from a concrete website. */
@Component
public class NewsCollectionTemplateCatalog {
    private static final String ATTACHMENTS =
        "a[href$='.pdf'],a[href$='.doc'],a[href$='.docx'],a[href$='.xls'],a[href$='.xlsx'],a[href$='.csv']";

    private final List<CollectionTemplate> templates = List.of(
        webTemplate("disclosure_list_detail", "信息披露：一级列表 + 二级详情/附件", "信息披露",
            "适合交易所、上市公司公告和监管披露网站。一级页面发现公告，二级页面提取正文和 PDF/Word/Excel 附件。",
            "a[href*='announcement'],a[href*='notice'],a[href*='disclosure'],a[href*='bulletin']," + ATTACHMENTS,
            "(?i)^https?://[^?#]+/.*(?:announcement|notice|disclosure|bulletin|pdf)(?:[/_.-]|[?#]).*$",
            "h1,.announcement-title,.article-title,.detail-title,.title",
            "article,.announcement-content,.article-content,.detail-content,.content,#content,.allZoom",
            List.of("读取一级公告列表", "按 URL 规则筛选详情", "提取二级正文", "下载并解析公告附件", "按 URL 与内容去重")),
        webTemplate("news_list_detail", "新闻资讯：一级列表 + 二级正文", "新闻资讯",
            "适合新闻、机构动态和专题资讯网站。一级页面发现文章链接，二级页面提取标题、时间、来源和正文。",
            "a[href*='/news/'],a[href*='/article/'],a[href*='/detail/'],a[href*='/content/']",
            "(?i)^https?://[^?#]+/.*(?:news|article|detail|content)(?:[/_.-]|[?#]).*$",
            "h1,.news-title,.article-title,.detail-title,.title,[itemprop='headline']",
            "article,.news-content,.article-content,.detail-content,.content,#content,[itemprop='articleBody']",
            List.of("读取一级新闻列表", "筛选站内详情链接", "提取二级标题与正文", "标准化时间和来源", "按 URL 与内容去重")),
        structuredFlashTemplate(),
        webTemplate("document_announcement_list", "公告文件：一级列表 + 文档原文", "信息披露",
            "适合一级页面直接提供 PDF、Word、Excel 或 CSV 下载链接的公告栏目。",
            ATTACHMENTS,
            "(?i)^https?://[^?#]+/.*\\.(?:pdf|docx?|xlsx?|csv)(?:[?#].*)?$",
            "h1,.announcement-title,.article-title,.title",
            "body",
            List.of("读取一级公告列表", "识别文档链接", "下载原始附件", "抽取文档文本并分块", "按附件 URL 去重")),
        new CollectionTemplate("rss_feed", "RSS/Atom 实时资讯", "标准订阅",
            "适合提供 RSS 或 Atom 的资讯网站；无需配置 CSS 选择器。",
            "RSS", "0 */10 * * * *",
            Map.of("templateCode", "rss_feed", "templateVersion", 1, "sleepMillis", 500,
                "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN"),
            emptyRule(), List.of("读取 RSS/Atom", "解析条目元数据与正文", "按链接与内容去重"),
            List.of("填写订阅地址和允许域名", "首次启用前仍需完成 robots.txt、网站条款和版权审查"))
    );

    public List<CollectionTemplate> templates() {
        return templates;
    }

    private static CollectionTemplate structuredFlashTemplate() {
        String disclaimer = "快讯内容版权归原发布机构及原作者所有，仅用于内部资讯分析，不构成投资建议；"
            + "启用前请确认已获得符合网站条款及适用法律的授权。";
        return new CollectionTemplate("realtime_flash_cursor",
            "电报/7×24 实时快讯：JSON 接口 + 游标断点", "实时快讯",
            "适合财联社电报、7×24 快讯等持续更新的信息流。通过页面使用的 JSON 接口采集，按游标断点续传，并按资讯 ID 与内容去重。",
            "STRUCTURED_FLASH", "0 */2 * * * *",
            Map.ofEntries(
                Map.entry("templateCode", "realtime_flash_cursor"),
                Map.entry("templateVersion", 1),
                Map.entry("request", Map.of("url", "", "signer", "NONE", "omitBlankQueryParameters", true,
                    "query", Map.of("max_time", "${cursor}", "page_size", "${pageSize}"))),
                Map.entry("response", Map.of("successPath", "", "successValue", "",
                    "errorMessagePath", "message", "itemsPath", "data",
                    "nextStateFromLastItem", Map.of("cursor", "id"))),
                Map.entry("mapping", Map.ofEntries(
                    Map.entry("id", "id"), Map.entry("cursor", "id"),
                    Map.entry("title", List.of("title", "content")),
                    Map.entry("content", List.of("content", "title")),
                    Map.entry("summary", "summary"), Map.entry("author", "source"),
                    Map.entry("defaultAuthor", ""), Map.entry("publishTime", "time"),
                    Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                    Map.entry("sourceUrl", "${url}"),
                    Map.entry("staticMetadata", Map.of("channel", "realtime-flash")))),
                Map.entry("compliance", Map.of("categories", List.of("7×24实时快讯"),
                    "tags", List.of("实时快讯"), "legalRisk", true, "legalDisclaimer", disclaimer)),
                Map.entry("initialState", Map.of("cursor", "")),
                Map.entry("itemLimit", 50), Map.entry("maxPagesPerRun", 200),
                Map.entry("initialBackfillHours", 24), Map.entry("numericCursorBoundary", true),
                Map.entry("sleepMillis", 300), Map.entry("minimumContentChars", 1),
                Map.entry("timeoutMillis", 20000), Map.entry("zoneId", "Asia/Shanghai"),
                Map.entry("language", "zh-CN"), Map.entry("legalRisk", true),
                Map.entry("legalDisclaimer", disclaimer)),
            emptyRule(),
            List.of("定位页面使用的 JSON 快讯接口", "配置请求参数与响应数组路径", "映射资讯 ID、游标、标题、正文和时间",
                "按持久化游标断点续采", "按资讯 ID 与内容去重"),
            List.of("模板中的 API 地址和 JSON 字段路径必须按目标站点调整",
                "不得绕过登录、付费或访问控制，启用前必须完成网站条款、版权与数据授权审查"));
    }

    private static CollectionTemplate webTemplate(String code, String name, String category, String description,
                                                   String linkSelector, String urlPattern, String titleSelector,
                                                   String contentSelector, List<String> workflow) {
        Pattern.compile(urlPattern);
        return new CollectionTemplate(code, name, category, description, "WEB_LIST", "0 */10 * * * *",
            Map.of("templateCode", code, "templateVersion", 1, "sleepMillis", 1000,
                "timeoutMillis", 30000, "zoneId", "Asia/Shanghai", "language", "zh-CN",
                "attachmentSelector", ATTACHMENTS, "attachmentAllowedDomains", ""),
            new RuleTemplate("", linkSelector, titleSelector, contentSelector,
                ".author,.source,.publisher,.article-source,[itemprop='author']",
                "time,.time,.publish-time,.publish-date,.article-time,[itemprop='datePublished']", urlPattern),
            workflow, List.of("模板选择器需要按目标站点 DOM 微调", "动态渲染页面应优先寻找官方 JSON 接口或使用专用采集器",
                "启用前必须执行 robots.txt、网站条款、版权与数据授权审查"));
    }

    private static RuleTemplate emptyRule() {
        return new RuleTemplate("", "", "", "", "", "", "");
    }

    public record CollectionTemplate(String code, String name, String category, String description,
                                     String sourceType, String scheduleCron, Map<String, Object> configuration,
                                     RuleTemplate rule, List<String> workflow, List<String> notes) { }

    public record RuleTemplate(String listSelector, String linkSelector, String titleSelector,
                               String contentSelector, String authorSelector, String publishTimeSelector,
                               String urlPattern) { }
}
