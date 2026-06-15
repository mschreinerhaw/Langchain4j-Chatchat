package com.chatchat.tools.builtin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class BingWebSearch {

    public static void main(String[] args) throws Exception {

        // =========================
        // 1️⃣ 查询词（模拟你的场景）
        // =========================
        String query = "今天A股市场热点";

        // =========================
        // 2️⃣ 按你这个 URL 拼接
        // =========================
        String url = "https://cn.bing.com/search?go=搜索&q=今天A股市场热点";
//                URLEncoder.encode(query, StandardCharsets.UTF_8);

        System.out.println("请求URL: " + url);

        // =========================
        // 3️⃣ 请求 Bing
        // =========================
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(15000)
                .get();

        // =========================
        // 4️⃣ 解析结果（Bing Web结构）
        // =========================
        Elements results = doc.select("li.b_algo");

        if (results.isEmpty()) {
            System.out.println("⚠️ 没解析到结果，打印HTML检查结构");
            System.out.println(doc.html().substring(0, 2000));
            return;
        }

        int i = 1;

        for (Element r : results) {

            Element titleEl = r.selectFirst("h2 a");
            Element snippetEl = r.selectFirst(".b_caption p");

            if (titleEl == null) continue;

            String title = titleEl.text();
            String link = titleEl.attr("href");
            String snippet = snippetEl != null ? snippetEl.text() : "";

            System.out.println("[" + i++ + "]");
            System.out.println("标题: " + title);
            System.out.println("链接: " + link);
            System.out.println("摘要: " + snippet);
            System.out.println("--------------------------------");
        }

        System.out.println("完成");
    }
}