package com.chatchat.tools.builtin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;

public class SiteSearchExample {

    public static void main(String[] args) {

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(false) // 调试用 false
            );

            BrowserContext context = browser.newContext();

            Page page = context.newPage();

            // =========================
            // 1️⃣ 打开目标网站
            // =========================
            String siteUrl = "https://www.sse.com.cn/"; // 改成你的站点
            page.navigate(siteUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            page.locator("text=精确搜索").click();

            // =========================
            // 2️⃣ 找站内搜索框
            // =========================
            // 常见 selector（可按实际站点调整）
            Locator searchBox = page.locator(
                    "input[type='search'], input[name='q'], input[name='keyword'], .search-input"
            );

            searchBox.waitFor();

            // =========================
            // 3️⃣ 输入关键词
            // =========================
            String keyword = "贵州茅台";

            searchBox.fill(keyword);

            // =========================
            // 4️⃣ 触发搜索（3种兼容方式）
            // =========================

            // 方式1：回车
            searchBox.press("Enter");

            // 或方式2（有些站点是按钮）
//             page.click("button[type='submit'], .search-btn");

            // =========================
            // 5️⃣ 等待结果加载
            // =========================
            page.waitForTimeout(2000);

            page.waitForSelector(
                    ".result, .search-result, .post, li, article",
                    new Page.WaitForSelectorOptions().setTimeout(30000)
            );

            // =========================
            // 6️⃣ 抽取结果（通用模式）
            // =========================
            List<ElementHandle> results = page.querySelectorAll(
                    ".result, .search-result, article, li"
            );

            System.out.println("===== 站内搜索结果 =====");

            int i = 1;

            for (ElementHandle r : results) {

                String text = r.innerText();

                if (text == null || text.length() < 10) continue;

                System.out.println("[" + i++ + "]");
                System.out.println(text.substring(0, Math.min(text.length(), 200)));
                System.out.println("---------------------------");
            }

            browser.close();
        }
    }
}
