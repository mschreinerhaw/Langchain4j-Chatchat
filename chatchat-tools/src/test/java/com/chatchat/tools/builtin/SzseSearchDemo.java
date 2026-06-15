package com.chatchat.tools.builtin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SzseSearchDemo {

    public static void main(String[] args) {

        String baseUrl = "https://www.szse.cn/application/search/index.html?keyword=";
        String keyword = "北京文化";

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            BrowserContext context = browser.newContext();

            Page page = context.newPage();

            page.setDefaultTimeout(60000);
            page.setDefaultNavigationTimeout(60000);

            // =========================
            // 1️⃣ 打开深交所搜索页
            // =========================
            String url = baseUrl + URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            System.out.println("访问: " + url);

            page.navigate(url);

            // =========================
            // 2️⃣ 等待 JS 渲染（关键）
            // =========================
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(5000);

            // =========================
            // 3️⃣ 等待结果节点出现（重点）
            // =========================
            Locator results = page.locator(".el-table__row, .search-item, .result-item, li");

            System.out.println("结果节点数量: " + results.count());

            int index = 1;

            for (int i = 0; i < results.count(); i++) {

                Locator item = results.nth(i);

                String text = item.innerText();

                if (text == null || text.length() < 5) continue;

                // 过滤关键词
                if (!text.contains(keyword)) continue;

                String link = "";

                Locator a = item.locator("a");
                if (a.count() > 0) {
                    link = a.first().getAttribute("href");
                }

                System.out.println("[" + index++ + "]");
                System.out.println("内容: " + text);
                System.out.println("链接: " + link);
                System.out.println("---------------------");
            }

            browser.close();
        }
    }
}
