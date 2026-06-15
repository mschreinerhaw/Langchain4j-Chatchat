package com.chatchat.tools.builtin;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;

import com.microsoft.playwright.*;

public class BingPlaywrightSearch {

    public static void main(String[] args) {

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            );

            // 加速（保留）
            context.route("**/*", route -> {
                if (route.request().resourceType().equals("image")) {
                    route.abort();
                } else {
                    route.resume();
                }
            });

            Page page = context.newPage();

            page.setDefaultTimeout(60000);
            page.setDefaultNavigationTimeout(60000);

            // =========================
            // 1️⃣ 正确打开 Bing
            // =========================
            page.navigate("https://www.bing.com/", new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // =========================
            // 2️⃣ 等待输入框稳定
            // =========================
            page.waitForSelector("input[name='q']");

            // =========================
            // 3️⃣ 输入
            // =========================
            String query = "提供24小时全球股票行情";

            page.fill("input[name='q']", query);

            // 等待 input 触发 JS
            page.waitForTimeout(300);

            page.keyboard().press("Enter");

            // =========================
            // 4️⃣ 等待结果（关键改造）
            // =========================
            page.waitForSelector("li.b_algo, li.b_ans",
                    new Page.WaitForSelectorOptions().setTimeout(60000));

            // 不要再用 DOMCONTENTLOADED（这里是错点）
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);

            // =========================
            // 5️⃣ 抽取结果（增强版 selector）
            // =========================
            List<ElementHandle> results = page.querySelectorAll("li.b_algo, li.b_ans");

            System.out.println("===== Bing 搜索结果 =====");

            int i = 1;

            for (ElementHandle r : results) {

                ElementHandle titleEl = r.querySelector("h2 a");

                // fallback selector（很重要）
                if (titleEl == null) {
                    titleEl = r.querySelector("a");
                }

                if (titleEl == null) continue;

                ElementHandle snippetEl = r.querySelector(".b_caption p, p");

                String title = titleEl.innerText();
                String url = titleEl.getAttribute("href");
                String snippet = snippetEl != null ? snippetEl.innerText() : "";

                System.out.println("[" + i++ + "]");
                System.out.println("标题: " + title);
                System.out.println("链接: " + url);
                System.out.println("摘要: " + snippet);
                System.out.println("----------------------------------");
            }

            browser.close();
        }
    }
}