package com.chatchat.tools.builtin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SseFinalSearchTest {

    public static void main(String[] args) throws Exception {

        String keyword = "贵州茅台";

        String url = "https://www.sse.com.cn/home/search/index.shtml?webswd=" +
                URLEncoder.encode(keyword, StandardCharsets.UTF_8);

        System.out.println("URL: " + url);

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(15000)
                .get();

        // =========================
        // 🔥 正确结果结构（关键）
        // =========================
        Elements items = doc.select(".search-result-list li, .result-list li, li");

        System.out.println("匹配节点数: " + items.size());

        int i = 1;

        for (Element item : items) {

            String text = item.text();

            if (text == null || text.length() < 10) continue;

            // 过滤：必须包含关键词
            if (!text.contains("贵州茅台") && !text.contains("600519")) continue;

            Element a = item.selectFirst("a");
            String link = a != null ? a.attr("href") : "";

            System.out.println("[" + i++ + "]");
            System.out.println("内容: " + text);
            System.out.println("链接: " + link);
            System.out.println("----------------------");
        }

        System.out.println("完成");
    }
}