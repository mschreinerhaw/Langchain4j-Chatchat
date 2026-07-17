package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects selected headline areas and, for exchanges, optional official market snapshots. */
@Component
public class ExchangeHomeNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public ExchangeHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper) {
        this(sink, objectMapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ExchangeHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.EXCHANGE_HOME || sourceType == NewsSourceType.NEWS_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        try {
            collectHeadlines(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("headlines: " + ex.getMessage());
        }
        if (hasMarketConfiguration(source)) {
            try {
                collectMarketSnapshot(source, counters);
            } catch (Exception ex) {
                counters.failed++;
                errors.add("market: " + ex.getMessage());
            }
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private boolean hasMarketConfiguration(NewsSource source) {
        return stringConfig(source, "marketUrlTemplate", null) != null
            && !stringList(source.configuration().get("marketCodes")).isEmpty();
    }

    private void collectHeadlines(NewsSource source, Counters counters) throws Exception {
        String html = get(source.entryUrl(), source);
        Document document = Jsoup.parse(html, source.entryUrl());
        String selector = stringConfig(source, "headlineSelector", null);
        if (selector == null) throw new IllegalArgumentException("headlineSelector is required");
        int limit = intConfig(source, "headlineLimit", 12);
        Map<String, String> headlines = new LinkedHashMap<>();
        for (Element element : document.select(selector)) {
            if (headlines.size() >= limit) break;
            String title = element.text().trim();
            String url = element.absUrl("href");
            if (!title.isBlank() && !url.isBlank()) headlines.putIfAbsent(url, title);
        }
        if (headlines.isEmpty()) throw new IllegalStateException("no configured homepage headlines were found");
        StringBuilder content = new StringBuilder("资讯首页当前要闻：\n");
        int index = 1;
        for (var item : headlines.entrySet()) {
            content.append(index++).append(". ").append(item.getValue()).append("；链接：").append(item.getKey()).append('\n');
        }
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "要闻快照", content.toString(),
            "从资讯首页指定要闻区域提取的最新标题集合。", source.name(), snapshotUrl(source, "headlines"),
            Instant.now(), language(source), List.of("首页要闻"), List.of("首页", "要闻"),
            Map.of("transport", "selected-home", "provider", provider(source), "headlineCount", headlines.size()))));
    }

    private void collectMarketSnapshot(NewsSource source, Counters counters) throws Exception {
        String template = stringConfig(source, "marketUrlTemplate", null);
        List<String> codes = stringList(source.configuration().get("marketCodes"));
        if (template == null || codes.isEmpty()) throw new IllegalArgumentException("market URL and codes are required");
        List<String> snapshots = new ArrayList<>();
        for (String code : codes) {
            JsonNode root = objectMapper.readTree(get(template.replace("{code}", code), source));
            snapshots.add("SSE".equals(provider(source)) ? sseSnapshot(root, code) : szseSnapshot(root, code));
        }
        String content = "交易所首页当日行情摘要：\n" + String.join("\n", snapshots)
            + "\n数据来自交易所首页使用的官方行情接口，数值以接口行情时间为准。";
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "当日行情快照", content,
            "主要指数最新点位、涨跌幅、成交量和成交额。", source.name(), snapshotUrl(source, "market"),
            Instant.now(), language(source), List.of("市场行情"), List.of("首页", "行情", "指数"),
            Map.of("transport", "exchange-home", "provider", provider(source), "indexCount", snapshots.size()))));
    }

    private String sseSnapshot(JsonNode root, String code) {
        JsonNode snap = root.path("snap");
        if (!snap.isArray() || snap.size() < 10) throw new IllegalArgumentException("invalid SSE snapshot for " + code);
        return String.format("%s（%s）：最新 %s，涨跌 %s，涨跌幅 %s%%，成交量 %s，成交额 %s；行情日期 %s，时间 %s。",
            snap.path(0).asText(code), code, snap.path(5).asText(), snap.path(6).asText(), snap.path(7).asText(),
            snap.path(8).asText(), snap.path(9).asText(), root.path("date").asText(), root.path("time").asText());
    }

    private String szseSnapshot(JsonNode root, String code) {
        JsonNode data = root.path("data");
        if (!data.isObject()) throw new IllegalArgumentException("invalid SZSE snapshot for " + code);
        return String.format("%s（%s）：最新 %s，涨跌 %s，涨跌幅 %s%%，开盘 %s，最高 %s，最低 %s，成交量 %s，成交额 %s；行情时间 %s。",
            data.path("name").asText(code), code, data.path("now").asText(), data.path("delta").asText(),
            data.path("deltaPercent").asText(), data.path("open").asText(), data.path("high").asText(),
            data.path("low").asText(), data.path("volume").asText(), data.path("amount").asText(),
            data.path("marketTime").asText(root.path("datetime").asText()));
    }

    private String get(String url, NewsSource source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "text/html,application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String snapshotUrl(NewsSource source, String kind) {
        return source.entryUrl() + (source.entryUrl().contains("?") ? "&" : "?") + "chatchatSnapshot=" + kind;
    }

    private String provider(NewsSource source) {
        return stringConfig(source, "provider", "").toUpperCase();
    }

    private String language(NewsSource source) {
        return stringConfig(source, "language", "zh-CN");
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private static class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;

        void add(NewsAcceptance acceptance) {
            if (acceptance == NewsAcceptance.ACCEPTED) accepted++;
            else if (acceptance == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }
    }
}
