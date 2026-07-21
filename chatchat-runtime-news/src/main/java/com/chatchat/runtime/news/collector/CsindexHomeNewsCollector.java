package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the five index cards and chart series displayed on the CSI homepage. */
@Component
public class CsindexHomeNewsCollector implements NewsCollector {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public CsindexHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper) {
        this(sink, objectMapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    CsindexHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.CSINDEX_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        try {
            JsonNode root = readJson(get(stringConfig(source, "indexSeriesUrl",
                "https://www.csindex.com.cn/csindex-home/homePage/indexMainAll"), source));
            requireSuccess(root, "CSI index series");
            JsonNode data = root.path("data");
            JsonNode indices = data.path("indexMainAlls");
            if (!indices.isArray() || indices.isEmpty()) {
                throw new IllegalStateException("CSI index series response has no indexMainAlls");
            }
            List<String> indexCodes = stringList(source.configuration().get("indexCodes"),
                List.of("000300", "000001", "000680", "000016", "000688"));
            String tradeDate = data.path("tradeDate").asText("");
            for (JsonNode index : indices) {
                if (!indexCodes.contains(index.path("indexCode").asText(""))) continue;
                RawNewsItem item = toItem(source, index, tradeDate);
                discovered++;
                NewsAcceptance result = sink.accept(item);
                if (result == NewsAcceptance.ACCEPTED) accepted++;
                else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                else rejected++;
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 0, null);
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, ex.getMessage());
        }
    }

    private RawNewsItem toItem(NewsSource source, JsonNode index, String tradeDate) throws Exception {
        String code = index.path("indexCode").asText("").trim();
        String name = index.path("indexNameCnAbbr").asText(code).trim();
        if (code.isBlank()) throw new IllegalStateException("CSI index card has no indexCode");
        List<Map<String, Object>> closeHistory = series(index.path("indexTables"), "close");
        String startDate = closeHistory.isEmpty() ? tradeDate : String.valueOf(closeHistory.get(0).get("tradeDate"));
        String endDate = closeHistory.isEmpty() ? tradeDate
            : String.valueOf(closeHistory.get(closeHistory.size() - 1).get("tradeDate"));
        List<Map<String, Object>> peHistory = peHistory(source, code, startDate, endDate);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "csindex-home-api");
        metadata.put("provider", "CSINDEX");
        metadata.put("indexCode", code);
        metadata.put("indexName", name);
        metadata.put("tradeDate", tradeDate);
        metadata.put("close", number(index.path("close")));
        metadata.put("changePct", index.path("changePct").asText(""));
        metadata.put("tradingValue100MCny", number(index.path("tradingValue")));
        metadata.put("closeHistory", closeHistory);
        metadata.put("peTtmHistory", peHistory);
        metadata.put("legalRisk", booleanConfig(source, "legalRisk", true));
        metadata.put("legalDisclaimer", stringConfig(source, "legalDisclaimer",
            "数据及相关指数、商标权益归中证指数有限公司及相关权利人所有；仅用于内部资讯检索和研究，不得未经授权再分发或用于商业用途，不构成投资建议。"));

        Object latestPe = peHistory.isEmpty() ? "-" : peHistory.get(peHistory.size() - 1).get("peTtm");
        String content = "%s（%s）更新日期 %s：收盘 %s，涨跌幅 %s，成交额 %s 亿元；滚动市盈率 %s。"
            .formatted(name, code, displayDate(tradeDate), index.path("close").asText("-"),
                index.path("changePct").asText("-"), index.path("tradingValue").asText("-"), latestPe)
            + "历史图表包含 " + closeHistory.size() + " 个收盘点和 " + peHistory.size() + " 个滚动市盈率点，"
            + "区间为 " + displayDate(startDate) + " 至 " + displayDate(endDate) + "。";
        String sourceUrl = source.entryUrl() + (source.entryUrl().contains("#") ? "&" : "#") + "indexCode=" + code;
        return new RawNewsItem(source, name + "（" + code + "）指数图数据", content,
            name + "收盘、涨跌幅、成交额及历史收盘/滚动市盈率序列", "中证指数有限公司", sourceUrl,
            publishTime(tradeDate, source), stringConfig(source, "language", "zh-CN"), List.of("指数行情"),
            List.of("中证指数", name, code, "指数图", "法律声明"), Map.copyOf(metadata));
    }

    private List<Map<String, Object>> peHistory(NewsSource source, String code, String startDate, String endDate)
        throws Exception {
        if (startDate.isBlank() || endDate.isBlank()) return List.of();
        String template = stringConfig(source, "peHistoryUrlTemplate",
            "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe?indexCode={code}&startDate={startDate}&endDate={endDate}");
        String url = template.replace("{code}", encode(code)).replace("{startDate}", encode(startDate))
            .replace("{endDate}", encode(endDate));
        JsonNode root = readJson(get(url, source));
        requireSuccess(root, "CSI PE history for " + code);
        return series(root.path("data"), "peg");
    }

    private List<Map<String, Object>> series(JsonNode nodes, String valueField) {
        if (!nodes.isArray()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String date = node.path("tradeDate").asText("");
            if (date.isBlank() || !node.path(valueField).isNumber()) continue;
            result.add(Map.of("tradeDate", date,
                "peg".equals(valueField) ? "peTtm" : valueField, number(node.path(valueField))));
        }
        return List.copyOf(result);
    }

    private Number number(JsonNode node) {
        if (node.isIntegralNumber()) return node.longValue();
        return node.decimalValue();
    }

    private Instant publishTime(String value, NewsSource source) {
        try {
            ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
            return LocalDate.parse(value, BASIC_DATE).atStartOfDay(zone).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String displayDate(String value) {
        if (value == null || value.length() != 8) return value;
        return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6);
    }

    private JsonNode readJson(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private void requireSuccess(JsonNode root, String label) {
        if (!"200".equals(root.path("code").asText())) {
            throw new IllegalStateException(label + " failed: " + root.path("msg").asText("unknown error"));
        }
    }

    private String get(String url, NewsSource source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
            .header("Accept", "application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean booleanConfig(NewsSource source, String key, boolean fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private List<String> stringList(Object value, List<String> fallback) {
        if (!(value instanceof List<?> list)) return fallback;
        List<String> result = list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        return result.isEmpty() ? fallback : result;
    }
}
