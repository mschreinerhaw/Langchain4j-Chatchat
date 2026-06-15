package com.chatchat.tools.builtin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class FinanceSiteSmartSearch {

    public static void main(String[] args) {
            String baseUrl = "https://www.sse.com.cn/home/search/index.shtml?webswd=";
            String keyword = "贵州茅台";
            String stockCode = "600519";

            try (Playwright playwright = Playwright.create()) {

                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions()
                                .setHeadless(false)
                );

                BrowserContext context = browser.newContext(
                        new Browser.NewContextOptions()
                                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                );

                Page page = context.newPage();
                page.setDefaultTimeout(60000);
                page.setDefaultNavigationTimeout(60000);

                // =========================
                // 1️⃣ 直接进入搜索结果页（最稳定）
                // =========================
                String url = baseUrl + URLEncoder.encode(keyword, StandardCharsets.UTF_8);

                System.out.println("访问URL: " + url);

                page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                // =========================
                // 2️⃣ 等待结果加载
                // =========================
                page.waitForTimeout(3000);

                System.out.println("当前URL: " + page.url());

                // =========================
                // 3️⃣ 提取结果
                // =========================
                List<ElementHandle> items = page.querySelectorAll(
                        ".search-result-list li, .result-list li, li"
                );

                System.out.println("结果数量: " + items.size());

                int index = 1;

                for (ElementHandle item : items) {

                    String text = "";
                    try {
                        text = item.innerText();
                    } catch (Exception ignored) {}

                    if (text == null || text.length() < 10) continue;

                    // =========================
                    // 4️⃣ 金融增强过滤（核心）
                    // =========================
                    boolean hitKeyword = text.contains(keyword);
                    boolean hitCode = text.contains(stockCode);

                    if (!hitKeyword && !hitCode) continue;

                    ElementHandle a = item.querySelector("a");
                    String link = a != null ? a.getAttribute("href") : "";

                    // =========================
                    // 5️⃣ 输出结构化结果
                    // =========================
                    System.out.println("[" + index++ + "]");
                    System.out.println("标题: " + text);
                    System.out.println("链接: " + link);
                    System.out.println("--------------------------------");
                }

                browser.close();
            }
        }
    }

