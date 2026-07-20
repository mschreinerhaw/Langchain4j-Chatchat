package com.chatchat.mcpserver.notification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationChannelContentRendererTest {

    private final NotificationChannelContentRenderer renderer = new NotificationChannelContentRenderer();

    @Test
    void emailGetsStyledHtmlAndPlainTextAlternative() {
        String markdown = """
            # 今日市场热点分析

            **判断：** 利好

            | 项目 | 内容 |
            | --- | --- |
            | 事件 | 央行净投放 |
            """;

        Map<String, Object> rendered = renderer.render(NotificationChannel.EMAIL, Map.of("content", markdown));

        assertThat(rendered.get("contentHtml").toString())
            .contains("<!doctype html>", "<h1 style=", "<table style=", "<strong style=")
            .doesNotContain("# 今日市场热点分析", "**判断：**");
        assertThat(rendered.get("contentPlain").toString())
            .contains("今日市场热点分析", "判断： 利好")
            .doesNotContain("**", "# 今日");
        assertThat(rendered).containsEntry("contentFormat", "HTML_WITH_PLAIN_TEXT_FALLBACK");
    }

    @Test
    void instantMessagingChannelsKeepImmutableMarkdown() {
        String markdown = "### 今日热点\n- **事件**：央行净投放";

        Map<String, Object> dingTalk = renderer.render(
            NotificationChannel.DINGTALK, Map.of("content", markdown));
        Map<String, Object> weCom = renderer.render(
            NotificationChannel.WECHAT_WORK, Map.of("content", markdown));

        assertThat(dingTalk).containsEntry("content", markdown)
            .containsEntry("contentFormat", "DINGTALK_MARKDOWN");
        assertThat(weCom).containsEntry("content", markdown)
            .containsEntry("contentFormat", "WECOM_MARKDOWN");
    }

    @Test
    void rawHtmlIsEscapedAndUnsafeUrlsAreSanitized() {
        String markdown = "<script>alert('x')</script>\n[危险](javascript:alert(1))";

        String html = renderer.render(NotificationChannel.EMAIL, Map.of("content", markdown))
            .get("contentHtml").toString();

        assertThat(html).doesNotContain("<script>", "href=\"javascript:")
            .contains("&lt;script&gt;");
    }

    @Test
    void emailMovesWebReferencesToFooterAndOmitsInternalEvidence() {
        String markdown = """
            # 今日市场热点分析

            市场情绪回暖【网页10】[网页2]。

            ## 工具执行证据

            1. `web_search`：成功，输出摘要=内部调试信息。
            """;
        Map<String, Object> reference = Map.of(
            "title", "央行公开市场业务交易公告",
            "url", "https://www.pbc.gov.cn/example",
            "text", "中国人民银行公开市场操作信息"
        );

        Map<String, Object> rendered = renderer.render(NotificationChannel.EMAIL, Map.of(
            "content", markdown,
            "references", List.of(reference, reference)
        ));

        assertThat(rendered.get("contentHtml").toString())
            .contains("引用来源", "央行公开市场业务交易公告", "href=\"https://www.pbc.gov.cn/example\"")
            .doesNotContain("网页10", "网页2", "工具执行证据", "内部调试信息");
        assertThat(rendered.get("contentPlain").toString())
            .contains("引用来源", "https://www.pbc.gov.cn/example")
            .doesNotContain("网页10", "工具执行证据");
    }
}
