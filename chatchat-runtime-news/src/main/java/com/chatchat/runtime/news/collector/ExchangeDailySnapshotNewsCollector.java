package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
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
import java.util.Locale;
import java.util.Map;

/** Collects idempotent daily market snapshots from the official SSE and SZSE data APIs. */
@Component
public class ExchangeDailySnapshotNewsCollector implements NewsCollector {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> SSE_QUOTE_FIELDS = List.of("code", "name", "open", "high", "low", "last",
        "prev_close", "chg_rate", "volume", "amount", "tradephase", "change", "amp_rate");
    private static final List<SzseSnapshotSpec> SZSE_SNAPSHOT_SPECS = List.of(
        new SzseSnapshotSpec("1815_stock_snapshot", "tab1", "EQUITY", false),
        new SzseSnapshotSpec("1815_stock_snapshot", "tab2", "FUND", false),
        new SzseSnapshotSpec("1815_stock_snapshot", "tab3", "BOND", false),
        new SzseSnapshotSpec("1815_stock_snapshot", "tab4", "REPO", false),
        new SzseSnapshotSpec("1815_stock_snapshot", "tab6", "OPTION", false),
        new SzseSnapshotSpec("1826_snapshot", "tab1", "INDEX", true));

    private final NewsItemSink sink;
    private final ObjectMapper mapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public ExchangeDailySnapshotNewsCollector(NewsItemSink sink, ObjectMapper mapper,
                                               NewsRuntimeProperties properties) {
        this(sink, mapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ExchangeDailySnapshotNewsCollector(NewsItemSink sink, ObjectMapper mapper,
                                       NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.mapper = mapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.EXCHANGE_DAILY_SNAPSHOT;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        String nextCursor = context.lastCursor();
        try {
            String provider = stringConfig(source, "provider", "SSE").toUpperCase(Locale.ROOT);
            if ("SSE".equals(provider)) nextCursor = collectSse(source, counters);
            else if ("SZSE".equals(provider)) nextCursor = collectSzse(source, context, counters);
            else throw new IllegalArgumentException("Unsupported daily-snapshot provider: " + provider);
            return result(context, source, counters, null, nextCursor);
        } catch (Exception ex) {
            counters.failed++;
            return result(context, source, counters, ex.getMessage(), context.lastCursor());
        }
    }

    private String collectSse(NewsSource source, Counters counters) throws Exception {
        String overviewDate = collectSseOverview(source, counters);
        String bondDate = collectSseBondDaily(source, counters);
        String quoteDate = collectSseQuotes(source, counters);
        return first(quoteDate, overviewDate, bondDate);
    }

    private String collectSseOverview(NewsSource source, Counters counters) throws Exception {
        String api = stringConfig(source, "sseQueryUrl", "https://query.sse.com.cn/commonQuery.do");
        JsonNode rows = result(get(withQuery(api, Map.of(
            "isPagination", "false", "sqlId", "COMMON_SSE_SJ_SCGM_C", "TRADE_DATE", "")), source),
            "SSE market overview");
        String date = "";
        for (JsonNode row : rows) {
            date = first(normalizeDate(row.path("TRADE_DATE").asText("")), date);
            Map<String, Object> metadata = base(source, "SSE", "sse-market-overview-api",
                "市场统计", "market_statistics_daily");
            metadata.put("tradeDate", date);
            metadata.put("section", row.path("PRODUCT_NAME").asText(""));
            copy(metadata, row, "SECURITY_NUM", "securityCount");
            copy(metadata, row, "TOTAL_VALUE", "marketCapitalization100MCny");
            copy(metadata, row, "NEGO_VALUE", "circulatingMarketCapitalization100MCny");
            copy(metadata, row, "TOTAL_TRADE_AMT", "turnover100MCny");
            accept(counters, item(source, "上交所市场总貌：" + row.path("PRODUCT_NAME").asText(""),
                source.entryUrl() + "#overview-" + row.path("PRODUCT_NAME").asText("") + "-" + date,
                date, "市场统计", metadata));
        }
        return date;
    }

    private String collectSseBondDaily(NewsSource source, Counters counters) throws Exception {
        String api = stringConfig(source, "sseQueryUrl", "https://query.sse.com.cn/commonQuery.do");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("isPagination", "true");
        params.put("pageHelp.pageSize", "100");
        params.put("pageHelp.pageNo", "1");
        params.put("pageHelp.beginPage", "1");
        params.put("pageHelp.cacheSize", "1");
        params.put("pageHelp.endPage", "1");
        params.put("sqlId", "COMMON_SSEBOND_SCSJ_SCTJ_CJSJ_ZQLXCJTJ_CX_L");
        params.put("START_DATE", "");
        params.put("END_DATE", "");
        params.put("MAX_TRADE_DATE", "1");
        JsonNode rows = result(get(withQuery(api, params), source), "SSE bond daily overview");
        String date = "";
        for (JsonNode row : rows) {
            date = first(normalizeDate(row.path("TRADE_DATE").asText("")), date);
            String category = row.path("TYPE").asText("");
            Map<String, Object> metadata = base(source, "SSE", "sse-bond-daily-api",
                "债券日度成交", "bond_market_daily");
            metadata.put("tradeDate", date);
            metadata.put("bondCategory", category);
            copy(metadata, row, "TYPE_CODE", "bondCategoryCode");
            copy(metadata, row, "VOLUME", "transactionCount");
            copy(metadata, row, "AMOUNT", "turnoverAmount10KCny");
            copy(metadata, row, "AVG_PRICE", "weightedAveragePrice");
            metadata.put("amountUnit", "10K-CNY");
            accept(counters, item(source, "上交所每日债券情况：" + category,
                stringConfig(source, "bondPageUrl", "https://www.sse.com.cn/market/bonddata/overview/day/")
                    + "#" + row.path("TYPE_CODE").asText("") + "-" + date,
                date, "债券日度成交", metadata));
        }
        return date;
    }

    private String collectSseQuotes(NewsSource source, Counters counters) throws Exception {
        String baseUrl = stringConfig(source, "quoteApiBaseUrl",
            "https://yunhq.sse.com.cn:32042/v1/sh1/list/exchange/");
        int pageSize = intConfig(source, "quotePageSize", 500);
        List<String> categories = stringList(source.configuration().get("quoteCategories"),
            List.of("equity", "index", "fwr", "bond"));
        String latestDate = "";
        for (String category : categories) {
            int begin = 0;
            int total;
            do {
                String url = withQuery(baseUrl + category, Map.of(
                    "select", String.join(",", SSE_QUOTE_FIELDS), "begin", String.valueOf(begin),
                    "end", String.valueOf(begin + pageSize)));
                JsonNode root = json(get(url, source));
                total = root.path("total").asInt(0);
                String date = normalizeDate(root.path("date").asText(""));
                latestDate = first(date, latestDate);
                List<Map<String, Object>> quotes = new ArrayList<>();
                JsonNode list = root.path("list");
                if (list.isArray()) for (JsonNode row : list) quotes.add(sseQuote(row, category, date));
                if (!quotes.isEmpty()) accept(counters, quotePage(source, "SSE", category, date, quotes));
                begin += pageSize;
            } while (begin < total);
        }
        return latestDate;
    }

    private Map<String, Object> sseQuote(JsonNode row, String category, String date) {
        Map<String, Object> quote = new LinkedHashMap<>();
        for (int index = 0; index < SSE_QUOTE_FIELDS.size() && index < row.size(); index++) {
            put(quote, sseQuoteField(SSE_QUOTE_FIELDS.get(index)), row.path(index));
        }
        quote.put("quoteCode", row.path(0).asText(""));
        quote.put("quoteName", row.path(1).asText(""));
        quote.put("tradeDate", date);
        quote.put("instrumentType", category.toUpperCase(Locale.ROOT));
        quote.put("amountUnit", "CNY");
        return Map.copyOf(quote);
    }

    private String sseQuoteField(String field) {
        return switch (field) {
            case "last" -> "close";
            case "prev_close" -> "previousClose";
            case "chg_rate" -> "changePct";
            case "tradephase" -> "tradingPhase";
            case "amp_rate" -> "amplitudePct";
            case "code" -> "quoteCode";
            case "name" -> "quoteName";
            default -> field;
        };
    }

    private String collectSzse(NewsSource source, NewsCollectContext context, Counters counters) throws Exception {
        String tradeDate = latestSzseDate(source);
        collectSzseOverview(source, counters, tradeDate);
        collectSzseDailyReport(source, counters, tradeDate, "scsj_gprdgk_after", "tab1", "txtQueryDate",
            "股票日度成交", "market_statistics_daily");
        collectSzseDailyReport(source, counters, tradeDate, "scsj_jjrdgk", "tab1", "txtQueryDate",
            "基金日度成交", "market_statistics_daily");
        collectSzseDailyReport(source, counters, tradeDate, "scsj_zqrdgk", "tab1", "txtDate",
            "债券日度成交", "bond_market_daily");
        return collectSzseQuotes(source, context.lastCursor(), counters, tradeDate);
    }

    private String latestSzseDate(NewsSource source) throws Exception {
        int lookback = intConfig(source, "lookbackDays", 10);
        LocalDate candidate = LocalDate.now(zone(source));
        for (int offset = 0; offset <= lookback; offset++) {
            String date = candidate.minusDays(offset).toString();
            JsonNode tab = szseTab(source, "1803_sczm", "tab1", Map.of("txtQueryDate", date, "PAGENO", "1"));
            if (tab.path("data").isArray() && !tab.path("data").isEmpty()) return date;
        }
        throw new IllegalStateException("SZSE daily snapshot has no data in the configured lookback window");
    }

    private void collectSzseOverview(NewsSource source, Counters counters, String date) throws Exception {
        JsonNode rows = szseTab(source, "1803_sczm", "tab1", Map.of("txtQueryDate", date, "PAGENO", "1")).path("data");
        for (JsonNode row : rows) {
            String category = clean(row.path("lbmc").asText(""));
            Map<String, Object> metadata = base(source, "SZSE", "szse-market-overview-report-api",
                "市场统计", "market_statistics_daily");
            metadata.put("tradeDate", date);
            metadata.put("section", category);
            copy(metadata, row, "zqsl", "securityCount");
            copy(metadata, row, "cjje", "turnover100MCny");
            copy(metadata, row, "sjzz", "marketCapitalization100MCny");
            copy(metadata, row, "ltsz", "circulatingMarketCapitalization100MCny");
            accept(counters, item(source, "深交所市场总貌：" + category,
                source.entryUrl() + "#overview-" + category + "-" + date, date, "市场统计", metadata));
        }
    }

    private void collectSzseDailyReport(NewsSource source, Counters counters, String date, String catalogId,
                                        String tabKey, String dateParameter, String dataset,
                                        String datasetCode) throws Exception {
        JsonNode rows = szseTab(source, catalogId, tabKey,
            Map.of(dateParameter, date, "tjzqlb", "D", "PAGENO", "1")).path("data");
        for (JsonNode row : rows) {
            String category = clean(first(row.path("lbmc").asText(""), row.path("zbmc").asText("")));
            Map<String, Object> metadata = base(source, "SZSE", "szse-daily-report-api", dataset, datasetCode);
            metadata.put("tradeDate", date);
            if ("bond_market_daily".equals(datasetCode)) metadata.put("bondCategory", category);
            else metadata.put("section", category);
            row.fields().forEachRemaining(entry -> {
                if (!List.of("lbmc", "zbmc").contains(entry.getKey())) put(metadata, entry.getKey(), entry.getValue());
            });
            if ("bond_market_daily".equals(datasetCode)) {
                copy(metadata, row, "cjje", "turnoverAmount10KCny");
                metadata.put("amountUnit", "10K-CNY");
            }
            accept(counters, item(source, "深交所" + dataset + "：" + category,
                source.entryUrl() + "#" + catalogId + "-" + category + "-" + date, date, dataset, metadata));
        }
    }

    private String collectSzseQuotes(NewsSource source, String rawCursor, Counters counters, String date) throws Exception {
        SnapshotCursor cursor = SnapshotCursor.parse(rawCursor, date);
        if (cursor.complete) return date + "|complete";
        int pageBudget = intConfig(source, "snapshotPagesPerRun", 50);
        int used = 0;
        for (int specIndex = cursor.specIndex; specIndex < SZSE_SNAPSHOT_SPECS.size(); specIndex++) {
            SzseSnapshotSpec spec = SZSE_SNAPSHOT_SPECS.get(specIndex);
            int page = specIndex == cursor.specIndex ? cursor.page : 1;
            boolean completedSpec = false;
            while (used < pageBudget) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("txtBeginDate", date);
                params.put("txtEndDate", date);
                params.put("PAGENO", String.valueOf(page));
                JsonNode tab = szseTab(source, spec.catalogId, spec.tabKey, params);
                JsonNode rows = tab.path("data");
                int pageCount = Math.max(1, tab.path("metadata").path("pagecount").asInt(1));
                List<Map<String, Object>> quotes = new ArrayList<>();
                if (rows.isArray()) for (JsonNode row : rows) quotes.add(szseQuote(row, spec, date));
                if (!quotes.isEmpty()) accept(counters, quotePage(source, "SZSE", spec.instrumentType, date, quotes));
                used++;
                page++;
                if (page > pageCount) {
                    completedSpec = true;
                    break;
                }
            }
            if (used >= pageBudget) {
                int nextSpec = completedSpec ? specIndex + 1 : specIndex;
                if (nextSpec >= SZSE_SNAPSHOT_SPECS.size()) return date + "|complete";
                return date + "|" + nextSpec + "|" + (completedSpec ? 1 : page);
            }
        }
        return date + "|complete";
    }

    private Map<String, Object> szseQuote(JsonNode row, SzseSnapshotSpec spec, String fallbackDate) {
        Map<String, Object> quote = new LinkedHashMap<>();
        String code = row.path(spec.index ? "zsdm" : "zqdm").asText("");
        String name = clean(row.path(spec.index ? "zsjc" : "zqjc").asText(""));
        quote.put("quoteCode", code);
        quote.put("quoteName", name);
        quote.put("tradeDate", first(row.path("jyrq").asText(""), fallbackDate));
        quote.put("instrumentType", spec.instrumentType);
        copy(quote, row, "qss", "previousClose");
        copy(quote, row, "ks", "open");
        copy(quote, row, "zg", "high");
        copy(quote, row, "zd", "low");
        copy(quote, row, "ss", "close");
        copy(quote, row, "sdf", "changePct");
        copy(quote, row, "cjgs", "volume10KUnits");
        copy(quote, row, "cjje", spec.index ? "amount100MCny" : "amount10KCny");
        copy(quote, row, "syl1", "peRatio");
        quote.put("amountUnit", spec.index ? "100M-CNY" : "10K-CNY");
        return Map.copyOf(quote);
    }

    private RawNewsItem quotePage(NewsSource source, String provider, String instrumentType, String date,
                                  List<Map<String, Object>> quotes) {
        Map<String, Object> metadata = base(source, provider,
            provider.toLowerCase(Locale.ROOT) + "-daily-quote-api", "行情", "market_quote_daily");
        metadata.put("tradeDate", date);
        metadata.put("instrumentType", instrumentType.toUpperCase(Locale.ROOT));
        metadata.put("snapshotMode", "OVERWRITE_BY_EXCHANGE_TYPE_DATE_SECURITY");
        metadata.put("quotes", List.copyOf(quotes));
        String page = stringConfig(source, "quotePageUrl", source.entryUrl());
        return new RawNewsItem(source, provider + " " + instrumentType + "每日行情快照",
            "交易日 " + date + "，本批包含 " + quotes.size() + " 条官方行情；同一交易日和证券代码重复采集时覆盖更新。",
            "交易所每日行情快照", provider, page + "#snapshot-" + instrumentType + "-" + date,
            instant(date, source), language(source), List.of("市场行情"),
            List.of(provider, instrumentType, "每日快照", "覆盖写"), Map.copyOf(metadata));
    }

    private JsonNode szseTab(NewsSource source, String catalogId, String tabKey, Map<String, String> extra)
        throws Exception {
        String api = stringConfig(source, "szseReportApiUrl", "https://www.szse.cn/api/report/ShowReport/data");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("SHOWTYPE", "JSON");
        params.put("CATALOGID", catalogId);
        params.put("TABKEY", tabKey);
        params.putAll(extra);
        JsonNode root = json(get(withQuery(api, params), source));
        if (!root.isArray()) throw new IllegalStateException("SZSE report " + catalogId + " returned no tab array");
        for (JsonNode tab : root) if (tabKey.equals(tab.path("metadata").path("tabkey").asText())) return tab;
        throw new IllegalStateException("SZSE report " + catalogId + " returned no " + tabKey);
    }

    private RawNewsItem item(NewsSource source, String title, String url, String date, String category,
                             Map<String, Object> metadata) {
        return new RawNewsItem(source, title, title + "；数据日期 " + date + "。", null,
            stringConfig(source, "providerName", source.name()), url, instant(date, source), language(source),
            List.of(category), List.of("官方数据", "每日快照", "覆盖写"), Map.copyOf(metadata));
    }

    private Map<String, Object> base(NewsSource source, String provider, String transport, String dataset,
                                     String datasetCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", provider);
        metadata.put("transport", transport);
        metadata.put("dataset", dataset);
        metadata.put("datasetCode", datasetCode);
        metadata.put("legalRisk", booleanConfig(source, "legalRisk", false));
        metadata.put("legalDisclaimer", stringConfig(source, "legalDisclaimer",
            "数据来自证券交易所官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；请以交易所最新披露为准。"));
        return metadata;
    }

    private void accept(Counters counters, RawNewsItem item) {
        if (counters.discovered >= properties.getMaxItemsPerRun()) return;
        counters.discovered++;
        NewsAcceptance acceptance = sink.accept(item);
        if (acceptance == NewsAcceptance.ACCEPTED) counters.accepted++;
        else if (acceptance == NewsAcceptance.DUPLICATE) counters.duplicate++;
        else counters.rejected++;
    }

    private NewsCollectResult result(NewsCollectContext context, NewsSource source, Counters counters,
                                     String error, String cursor) {
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, error, cursor);
    }

    private JsonNode result(String body, String label) throws Exception {
        JsonNode value = json(body).path("result");
        if (!value.isArray()) throw new IllegalStateException(label + " response has no result array");
        return value;
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private String get(String url, NewsSource source) throws Exception {
        int retries = Math.max(0, intConfig(source, "requestRetries", 2));
        long delay = Math.max(0, intConfig(source, "sleepMillis", 100));
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Referer", source.entryUrl())
                    .header("User-Agent", "Mozilla/5.0 ChatChat-MarketSnapshot/1.0")
                    .GET().build();
                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
                }
                if (delay > 0) Thread.sleep(delay);
                return response.body();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (Exception ex) {
                last = ex;
                if (attempt < retries) Thread.sleep(Math.max(500, delay * (attempt + 1)));
            }
        }
        throw last == null ? new IllegalStateException("Request failed: " + url) : last;
    }

    private String withQuery(String base, Map<String, String> params) {
        StringBuilder url = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        return url.toString();
    }

    private void copy(Map<String, Object> target, JsonNode source, String sourceField, String targetField) {
        put(target, targetField, source.path(sourceField));
    }

    private void put(Map<String, Object> target, String field, JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return;
        if (value.isNumber()) target.put(field, value.decimalValue());
        else target.put(field, clean(value.asText("")));
    }

    private String clean(String value) {
        return Jsoup.parse(value == null ? "" : value).text().replace('\u00a0', ' ').trim();
    }

    private String normalizeDate(String value) {
        String clean = value == null ? "" : value.trim().replace('/', '-');
        if (clean.matches("\\d{8}")) return clean.substring(0, 4) + "-" + clean.substring(4, 6) + "-" + clean.substring(6);
        return clean;
    }

    private Instant instant(String date, NewsSource source) {
        try { return LocalDate.parse(normalizeDate(date)).atStartOfDay(zone(source)).toInstant(); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private ZoneId zone(NewsSource source) {
        return ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
    }

    private String language(NewsSource source) { return stringConfig(source, "language", "zh-CN"); }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
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

    private static final class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;
    }

    private record SzseSnapshotSpec(String catalogId, String tabKey, String instrumentType, boolean index) { }

    private record SnapshotCursor(String date, int specIndex, int page, boolean complete) {
        static SnapshotCursor parse(String value, String currentDate) {
            if (value == null || value.isBlank()) return new SnapshotCursor(currentDate, 0, 1, false);
            String[] parts = value.split("\\|", -1);
            if (parts.length < 2 || !currentDate.equals(parts[0])) return new SnapshotCursor(currentDate, 0, 1, false);
            if ("complete".equals(parts[1])) return new SnapshotCursor(currentDate, 0, 1, true);
            try {
                return new SnapshotCursor(currentDate, Math.max(0, Integer.parseInt(parts[1])),
                    parts.length > 2 ? Math.max(1, Integer.parseInt(parts[2])) : 1, false);
            } catch (NumberFormatException ignored) {
                return new SnapshotCursor(currentDate, 0, 1, false);
            }
        }
    }
}
