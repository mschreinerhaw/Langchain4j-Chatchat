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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects official SSE/SZSE margin-trading and company distribution datasets. */
@Component
public class ExchangeMarketDataNewsCollector implements NewsCollector {
    private static final Pattern SZSE_LATEST_MONTH = Pattern.compile("value\\s*:\\s*['\"]([^'\"]*t\\d+_\\d+\\.html)['\"]");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public ExchangeMarketDataNewsCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                           NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ExchangeMarketDataNewsCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                    NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.EXCHANGE_MARKET_DATA;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        String provider = stringConfig(source, "provider", "SSE").toUpperCase(Locale.ROOT);
        String mode = stringConfig(source, "mode", "MARKET_PERFORMANCE");
        String nextCursor = context.lastCursor();
        try {
            if ("THREE_MARKET_OVERVIEW".equalsIgnoreCase(mode)) {
                nextCursor = collectThreeMarketOverview(source, counters);
            } else if ("FUND_SCALE".equalsIgnoreCase(mode)) {
                nextCursor = "SSE".equals(provider) ? collectSseFundScale(source, context, counters)
                    : "SZSE".equals(provider) ? collectSzseFundScale(source, context, counters)
                    : throwUnsupported(provider);
            } else if ("SSE".equals(provider)) collectSse(source, counters);
            else if ("SZSE".equals(provider)) collectSzse(source, counters);
            else throw new IllegalArgumentException("Unsupported exchange market-data provider: " + provider);
        } catch (Exception ex) {
            counters.failed++;
            errors.add(ex.getMessage());
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors),
            counters.failed == 0 ? nextCursor : context.lastCursor());
    }

    private String collectThreeMarketOverview(NewsSource source, Counters counters) throws Exception {
        LocalDate requested = LocalDate.now(zone(source));
        String api = stringConfig(source, "marketHighlightsApiUrl",
            "https://www.hkex.com.hk/chi/csm/ws/Highlightsearch.asmx/GetData");
        String url = withQuery(api, Map.of(
            "LangCode", "tc", "TDD", String.valueOf(requested.getDayOfMonth()),
            "TMM", String.valueOf(requested.getMonthValue()), "TYYYY", String.valueOf(requested.getYear())));
        JsonNode root = json(get(url, source, StandardCharsets.UTF_8));
        JsonNode rows = array(root, "data", "HKEX three-market highlights");
        if (rows.size() < 3) throw new IllegalStateException("HKEX three-market highlights response is incomplete");

        String tradeDate = marketHighlightDate(root.path("MaxRefDate").asText(""));
        JsonNode marketHeaders = rows.get(0).path("td");
        JsonNode segmentHeaders = rows.get(1).path("td");
        if (!marketHeaders.isArray() || marketHeaders.size() < 4 || !segmentHeaders.isArray()) {
            throw new IllegalStateException("HKEX three-market highlights headers are incomplete");
        }
        for (int marketIndex = 1; marketIndex <= 3 && !full(counters); marketIndex++) {
            String marketName = firstCell(marketHeaders.path(marketIndex));
            String marketCode = switch (marketIndex) {
                case 1 -> "HKEX";
                case 2 -> "SSE";
                default -> "SZSE";
            };
            List<String> segments = cells(segmentHeaders.path(marketIndex));
            List<Map<String, Object>> segmentData = new ArrayList<>();
            for (String segment : segments) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("segment", segment);
                segmentData.add(values);
            }
            Map<String, Object> rawMetrics = new LinkedHashMap<>();
            for (int rowIndex = 2; rowIndex < rows.size(); rowIndex++) {
                JsonNode cells = rows.get(rowIndex).path("td");
                if (!cells.isArray() || cells.size() <= marketIndex) continue;
                String label = firstCell(cells.path(0));
                List<String> values = cells(cells.path(marketIndex));
                rawMetrics.put(label, List.copyOf(values));
                String key = marketMetricKey(label);
                for (int segmentIndex = 0; segmentIndex < segmentData.size() && segmentIndex < values.size(); segmentIndex++) {
                    segmentData.get(segmentIndex).put(key, values.get(segmentIndex));
                }
            }

            Map<String, Object> metadata = baseMetadata(source, marketCode,
                "hkex-three-market-highlights-api", "沪深港市场汇总");
            metadata.put("market", marketName);
            metadata.put("marketCode", marketCode);
            metadata.put("tradeDate", tradeDate);
            metadata.put("segments", List.copyOf(segmentData));
            metadata.put("rawMetrics", Map.copyOf(rawMetrics));
            metadata.put("amountUnits", "香港市场为HKD，上海和深圳市场为RMB；市值单位亿元，成交金额单位百万元");
            String title = marketName.replaceAll("\\s*\\([^)]*\\)\\s*$", "") + "市场汇总 " + tradeDate;
            String content = title + "；板块：" + String.join("、", segments)
                + "；采集上市公司数、上市证券数、总市值、流通市值、平均市盈率、成交股数和成交金额等官方汇总指标。";
            accept(source, counters, item(source, title, content,
                source.entryUrl() + "#" + marketCode.toLowerCase(Locale.ROOT) + "-" + tradeDate,
                tradeDate, "沪深港市场汇总", "", marketName, metadata));
        }
        return tradeDate;
    }

    private String marketHighlightDate(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            return LocalDate.parse(value.trim(), DateTimeFormatter.ofPattern("MM/dd/uuuu")).toString();
        } catch (Exception ignored) {
            return displayDate(value);
        }
    }

    private List<String> cells(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) for (JsonNode value : node) values.add(Jsoup.parse(value.asText("")).text().trim());
        else if (!node.isMissingNode() && !node.isNull()) values.add(Jsoup.parse(node.asText("")).text().trim());
        return values;
    }

    private String firstCell(JsonNode node) {
        List<String> values = cells(node);
        return values.isEmpty() ? "" : values.get(0);
    }

    private String marketMetricKey(String label) {
        String normalized = label.replace('總', '总').replace('數', '数').replace('證', '证')
            .replace('內', '内').replace('業', '业').replace('額', '额').replace('場', '场');
        if (normalized.startsWith("上市公司总数")) return "listedCompanies";
        if (normalized.startsWith("上市H股总数")) return "listedHShares";
        if (normalized.startsWith("上市非H股内地企业总数")) return "listedNonHShareMainlandEnterprises";
        if (normalized.startsWith("上市证券总数")) return "listedSecurities";
        if (normalized.startsWith("总流通市值")) return "circulatingMarketCapitalization100MCurrency";
        if (normalized.startsWith("总市值")) return "marketCapitalization100MCurrency";
        if (normalized.startsWith("平均市盈率")) return "averagePeRatio";
        if (normalized.startsWith("总成交股数")) return "turnoverVolumeMillionShares";
        if (normalized.startsWith("市场总成交金额")) return "marketTurnoverMillionCurrency";
        if (normalized.startsWith("总成交金额")) return "turnoverMillionCurrency";
        return label;
    }

    private String throwUnsupported(String provider) {
        throw new IllegalArgumentException("Unsupported exchange fund-scale provider: " + provider);
    }

    private String collectSseFundScale(NewsSource source, NewsCollectContext context, Counters counters) throws Exception {
        String api = stringConfig(source, "fundScaleApiUrl", "https://query.sse.com.cn/commonQuery.do");
        int pageSize = intConfig(source, "fundScalePageSize", 100);
        JsonNode firstRoot = sseFundScalePage(source, api, 1, pageSize);
        JsonNode firstRows = array(firstRoot, "result", "SSE ETF scale");
        if (firstRows.isEmpty()) throw new IllegalStateException("SSE ETF scale response is empty");
        String scaleDate = firstRows.get(0).path("STAT_DATE").asText("");
        PageCursor checkpoint = PageCursor.parse(context.lastCursor());
        if (checkpoint != null && checkpoint.date.equals(scaleDate) && checkpoint.complete) return context.lastCursor();
        int startPage = checkpoint != null && checkpoint.date.equals(scaleDate) ? checkpoint.page : 1;
        int pageCount = Math.max(1, firstRoot.path("pageHelp").path("pageCount").asInt(1));
        for (int page = startPage; page <= pageCount; page++) {
            JsonNode rows = page == 1 ? firstRows : array(sseFundScalePage(source, api, page, pageSize), "result", "SSE ETF scale");
            for (JsonNode row : rows) {
                if (full(counters)) return scaleDate + ":" + page;
                accept(source, counters, fundScaleItem(source, "SSE", row.path("STAT_DATE").asText(scaleDate),
                    row.path("SEC_CODE").asText(""), row.path("SEC_NAME").asText(""),
                    row.path("TOT_VOL").asText(""), row.path("ETF_TYPE").asText("")));
            }
            if (full(counters) && page < pageCount) return scaleDate + ":" + (page + 1);
        }
        return scaleDate + ":complete";
    }

    private JsonNode sseFundScalePage(NewsSource source, String api, int page, int pageSize) throws Exception {
        return json(get(withQuery(api, Map.ofEntries(
            Map.entry("isPagination", "true"), Map.entry("pageHelp.pageSize", String.valueOf(pageSize)),
            Map.entry("pageHelp.pageNo", String.valueOf(page)), Map.entry("pageHelp.beginPage", String.valueOf(page)),
            Map.entry("pageHelp.cacheSize", "1"), Map.entry("pageHelp.endPage", String.valueOf(page)),
            Map.entry("sqlId", "COMMON_SSE_ZQPZ_ETFZL_XXPL_ETFGM_SEARCH_L"), Map.entry("STAT_DATE", ""))),
            source, StandardCharsets.UTF_8));
    }

    private String collectSzseFundScale(NewsSource source, NewsCollectContext context, Counters counters) throws Exception {
        String api = stringConfig(source, "fundScaleApiUrl",
            "https://www.szse.cn/api/report/ShowReport/data?SHOWTYPE=JSON&CATALOGID=scsj_fund_jjgm&jjlb=ETF");
        int lookbackDays = intConfig(source, "fundScaleLookbackDays", 10);
        JsonNode firstTab = null;
        String scaleDate = "";
        LocalDate candidate = LocalDate.now(zone(source)).minusDays(1);
        for (int offset = 0; offset <= lookbackDays; offset++) {
            String date = candidate.minusDays(offset).toString();
            JsonNode root = json(get(szseFundScaleUrl(api, date, 1), source, StandardCharsets.UTF_8));
            JsonNode tab = tab(root, "tab1", "SZSE ETF scale");
            if (tab.path("data").isArray() && !tab.path("data").isEmpty()) {
                firstTab = tab;
                scaleDate = date;
                break;
            }
        }
        if (firstTab == null) throw new IllegalStateException("SZSE ETF scale has no data in the configured lookback window");
        PageCursor checkpoint = PageCursor.parse(context.lastCursor());
        if (checkpoint != null && checkpoint.date.equals(scaleDate) && checkpoint.complete) return context.lastCursor();
        int startPage = checkpoint != null && checkpoint.date.equals(scaleDate) ? checkpoint.page : 1;
        int pageCount = Math.max(1, firstTab.path("metadata").path("pagecount").asInt(1));
        for (int page = startPage; page <= pageCount; page++) {
            JsonNode current = page == 1 ? firstTab
                : tab(json(get(szseFundScaleUrl(api, scaleDate, page), source, StandardCharsets.UTF_8)),
                    "tab1", "SZSE ETF scale");
            for (JsonNode row : current.path("data")) {
                if (full(counters)) return scaleDate + ":" + page;
                accept(source, counters, fundScaleItem(source, "SZSE", row.path("size_date").asText(scaleDate),
                    row.path("fund_code").asText(""), row.path("security_short_name").asText(""),
                    row.path("current_size").asText(""), "ETF"));
            }
            if (full(counters) && page < pageCount) return scaleDate + ":" + (page + 1);
        }
        return scaleDate + ":complete";
    }

    private String szseFundScaleUrl(String api, String date, int page) {
        return withQuery(api, Map.of("txtStart", date, "txtEnd", date, "TABKEY", "tab1", "PAGENO", String.valueOf(page)));
    }

    private RawNewsItem fundScaleItem(NewsSource source, String provider, String date, String code,
                                      String name, String scale, String fundType) {
        String exchange = "SSE".equals(provider) ? "上交所" : "深交所";
        Map<String, Object> metadata = baseMetadata(source, provider,
            "SSE".equals(provider) ? "sse-etf-scale-api" : "szse-etf-scale-report-api", "ETF规模");
        metadata.put("scaleDate", date);
        metadata.put("fundCode", code);
        metadata.put("fundName", name);
        metadata.put("fundType", fundType);
        metadata.put("fundScale10KUnits", scale);
        metadata.put("scaleUnit", "10K-fund-units");
        String title = exchange + "ETF规模：" + name + "（" + code + "）";
        String content = title + "；规模日期 " + date + "，基金规模/总份额 " + scale + " 万份。";
        return item(source, title, content, source.entryUrl() + "#ETF规模-" + code + "-" + date,
            date, "ETF规模", code, name, metadata);
    }

    private void collectSse(NewsSource source, Counters counters) throws Exception {
        int year = LocalDate.now(zone(source)).getYear();
        String marginUrl = stringConfig(source, "marginApiUrl", "https://query.sse.com.cn/commonSoaQuery.do");
        JsonNode summaryRoot = json(get(withQuery(marginUrl, Map.ofEntries(
            Map.entry("isPagination", "true"), Map.entry("pageHelp.pageSize", stringConfig(source, "marginSummaryLimit", "30")),
            Map.entry("pageHelp.pageNo", "1"), Map.entry("pageHelp.beginPage", "1"), Map.entry("pageHelp.cacheSize", "1"),
            Map.entry("pageHelp.endPage", "1"), Map.entry("stockCode", ""), Map.entry("beginDate", ""),
            Map.entry("endDate", ""), Map.entry("sqlId", "RZRQ_HZ_INFO"))), source, StandardCharsets.UTF_8));
        JsonNode summaries = array(summaryRoot, "result", "SSE margin summary");
        String latestDate = "";
        for (JsonNode row : summaries) {
            if (full(counters)) break;
            String date = row.path("opDate").asText("");
            if (latestDate.isBlank()) latestDate = date;
            accept(source, counters, marginItem(source, "SSE", "融资融券汇总", date, "", "", row,
                List.of("rzye", "rzmre", "rzche", "rqyl", "rqylje", "rqmcl", "rzrqjyzl"), source.entryUrl()));
        }
        if (!latestDate.isBlank() && !full(counters)) {
            JsonNode details = array(json(get(withQuery(marginUrl, Map.ofEntries(
                Map.entry("isPagination", "true"), Map.entry("pageHelp.pageSize", stringConfig(source, "marginDetailLimit", "100")),
                Map.entry("pageHelp.pageNo", "1"), Map.entry("pageHelp.beginPage", "1"), Map.entry("pageHelp.cacheSize", "1"),
                Map.entry("pageHelp.endPage", "1"), Map.entry("stockCode", ""), Map.entry("preStockCode", ""),
                Map.entry("beginDate", latestDate), Map.entry("endDate", latestDate), Map.entry("sqlId", "RZRQ_MX_INFO"))),
                source, StandardCharsets.UTF_8)), "result", "SSE margin detail");
            for (JsonNode row : details) {
                if (full(counters)) break;
                accept(source, counters, marginItem(source, "SSE", "融资融券明细", row.path("opDate").asText(latestDate),
                    row.path("stockCode").asText(""), row.path("securityAbbr").asText(""), row,
                    List.of("rzye", "rzmre", "rzche", "rqyl", "rqmcl", "rqchl"), source.entryUrl()));
            }
        }
        collectSseDistributions(source, counters, year, "现金分红", "COMMON_SSE_SJ_GPSJ_FHSG_SSGSFHQK_L",
            Map.of("CONDITION_AG", "1", "A_REG_DATE", String.valueOf(year)),
            List.of("A_BEFR_TAX_DIV", "A_AFTR_TAX_DIV", "A_REG_DATE", "A_DIV_DATE", "DIVIDEND_DATE"),
            stringConfig(source, "dividendPageUrl", "https://www.sse.com.cn/market/stockdata/dividends/dividend/"));
        collectSseDistributions(source, counters, year, "送股转增", "COMMON_SSE_SJ_GPSJ_FHSG_SG_L",
            Map.of("SEARCH_YEAR", String.valueOf(year), "COMPANY_CODE", ""),
            List.of("BONUS_RATIO", "CONVERT_RATIO", "A_REG_DATE", "A_DERIGHTS_DATE"),
            stringConfig(source, "bonusPageUrl", "https://www.sse.com.cn/market/stockdata/dividends/bonus/"));
    }

    private void collectSseDistributions(NewsSource source, Counters counters, int year, String category,
                                         String sqlId, Map<String, String> filters, List<String> fields,
                                         String pageUrl) throws Exception {
        if (full(counters)) return;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("isPagination", "true");
        query.put("pageHelp.pageSize", stringConfig(source, "distributionLimit", "100"));
        query.put("pageHelp.pageNo", "1");
        query.put("pageHelp.beginPage", "1");
        query.put("pageHelp.cacheSize", "1");
        query.put("pageHelp.endPage", "1");
        query.put("sqlId", sqlId);
        query.putAll(filters);
        String api = stringConfig(source, "distributionApiUrl", "https://query.sse.com.cn/commonQuery.do");
        JsonNode rows = array(json(get(withQuery(api, query), source, StandardCharsets.UTF_8)), "result", "SSE " + category);
        for (JsonNode row : rows) {
            if (full(counters)) break;
            String code = row.path("A_STOCK_CODE").asText("");
            String name = row.path("COMPANY_ABBR").asText(row.path("FULL_NAME").asText(""));
            Map<String, Object> metadata = baseMetadata(source, "SSE", "sse-distribution-api", category);
            metadata.put("year", year);
            metadata.put("securityCode", code);
            metadata.put("securityName", name);
            fields.forEach(field -> put(metadata, field, row.path(field)));
            if ("现金分红".equals(category)) {
                copy(metadata, row, "A_BEFR_TAX_DIV", "dividendPerShareBeforeTax");
                copy(metadata, row, "A_AFTR_TAX_DIV", "dividendPerShareAfterTax");
                copy(metadata, row, "A_REG_DATE", "recordDate");
                copy(metadata, row, "A_DIV_DATE", "exDividendDate");
                copy(metadata, row, "DIVIDEND_DATE", "paymentDate");
            } else {
                copy(metadata, row, "BONUS_RATIO", "bonusSharesPerTenShares");
                copy(metadata, row, "CONVERT_RATIO", "convertedSharesPerTenShares");
                copy(metadata, row, "A_REG_DATE", "recordDate");
                copy(metadata, row, "A_DERIGHTS_DATE", "exRightsDate");
            }
            String date = first(row.path("A_REG_DATE").asText(""), row.path("A_DERIGHTS_DATE").asText(""));
            String title = "上交所" + category + "：" + name + (code.isBlank() ? "" : "（" + code + "）");
            String content = title + "；" + joinFields(row, fields) + "。数据年度：" + year + "。";
            accept(source, counters, item(source, title, content, pageUrl + "#" + category + "-" + code + "-" + date,
                date, category, code, name, metadata));
        }
    }

    private void collectSzse(NewsSource source, Counters counters) throws Exception {
        String api = stringConfig(source, "marginApiUrl",
            "https://www.szse.cn/api/report/ShowReport/data?SHOWTYPE=JSON&CATALOGID=1837_xxpl&loading=first");
        JsonNode initial = json(get(api, source, StandardCharsets.UTF_8));
        if (!initial.isArray()) throw new IllegalStateException("SZSE margin response is not an array");
        JsonNode summary = tab(initial, "tab1", "SZSE margin summary");
        String date = summary.path("metadata").path("subname").asText("");
        for (JsonNode row : summary.path("data")) {
            if (full(counters)) break;
            accept(source, counters, marginItem(source, "SZSE", "融资融券汇总", date, "", "", row,
                List.of("jrrzmr", "jrrzye", "jrrjmc", "jrrjyl", "jrrjye", "jrrzrjye"), source.entryUrl()));
        }
        int detailPages = intConfig(source, "marginDetailPages", 5);
        for (int page = 1; page <= detailPages && !full(counters); page++) {
            JsonNode detail = page == 1 ? tab(initial, "tab2", "SZSE margin detail")
                : tab(json(get(withQuery(stripLoading(api), Map.of("TABKEY", "tab2", "tab2PAGENO", String.valueOf(page),
                    "txtDate", date)), source, StandardCharsets.UTF_8)), "tab2", "SZSE margin detail");
            for (JsonNode row : detail.path("data")) {
                if (full(counters)) break;
                accept(source, counters, marginItem(source, "SZSE", "融资融券明细", date,
                    row.path("zqdm").asText(""), Jsoup.parse(row.path("zqjc").asText("")).text(), row,
                    List.of("jrrzmr", "jrrzye", "jrrjmc", "jrrjyl", "jrrjye", "jrrzrjye"), source.entryUrl()));
            }
        }
        collectSzseDistributions(source, counters);
    }

    private void collectSzseDistributions(NewsSource source, Counters counters) throws Exception {
        if (full(counters)) return;
        String indexUrl = stringConfig(source, "monthlyIndexUrl", "https://www.szse.cn/market/periodical/month/index.html");
        String articleUrl = stringConfig(source, "monthlyArticleUrl", "");
        if (articleUrl.isBlank()) {
            String html = get(indexUrl, source, StandardCharsets.UTF_8);
            Matcher matcher = SZSE_LATEST_MONTH.matcher(html);
            if (!matcher.find()) throw new IllegalStateException("SZSE monthly report index has no latest article");
            articleUrl = URI.create(indexUrl).resolve(matcher.group(1)).toString();
        }
        Document article = Jsoup.parse(get(articleUrl, source, StandardCharsets.UTF_8), articleUrl);
        Element link = article.select("a[href]").stream().filter(a -> a.text().contains("分红派息配股")).findFirst()
            .orElseThrow(() -> new IllegalStateException("SZSE monthly report has no dividend table link"));
        String tableUrl = link.absUrl("href");
        if (tableUrl.startsWith("http://") && tableUrl.contains(".szse.cn/")) {
            tableUrl = "https://" + tableUrl.substring("http://".length());
        }
        Document table = Jsoup.parse(get(tableUrl, source, Charset.forName(stringConfig(source, "monthlyTableCharset", "GBK"))), tableUrl);
        int limit = intConfig(source, "distributionLimit", 250);
        int count = 0;
        for (Element row : table.select("tr")) {
            if (full(counters) || count >= limit) break;
            List<Element> cells = row.select("td");
            if (cells.size() < 14 || !cells.get(0).text().trim().matches("\\d{6}")) continue;
            String code = cells.get(0).text().trim();
            String name = cells.get(1).text().replaceAll("\\s+", "");
            String exDate = cells.get(10).text().trim();
            Map<String, Object> metadata = baseMetadata(source, "SZSE", "szse-monthly-report", "分红送股配股");
            metadata.put("securityCode", code);
            metadata.put("securityName", name);
            metadata.put("bonusShares", cells.get(2).text().trim());
            metadata.put("bonusPerShare", cells.get(3).text().trim());
            metadata.put("cashDividend", cells.get(4).text().trim());
            metadata.put("cashDividendPerShare", cells.get(5).text().trim());
            metadata.put("rightsShares", cells.get(6).text().trim());
            metadata.put("rightsPerShare", cells.get(7).text().trim());
            metadata.put("rightsPrice", cells.get(8).text().trim());
            metadata.put("fundsRaised", cells.get(9).text().trim());
            metadata.put("exDate", exDate);
            metadata.put("recordDate", cells.get(11).text().trim());
            metadata.put("exPrice", cells.get(12).text().trim());
            metadata.put("previousClose", cells.get(13).text().trim());
            metadata.put("monthlyReportUrl", articleUrl);
            String title = "深交所分红送股：" + name + "（" + code + "）";
            String content = title + "；送股率 " + cells.get(3).text().trim() + "，每股派息 "
                + cells.get(5).text().trim() + "，除净日 " + exDate + "，股权登记日 " + cells.get(11).text().trim() + "。";
            count++;
            accept(source, counters, item(source, title, content, tableUrl + "#" + code + "-" + exDate,
                exDate, "分红送股配股", code, name, metadata));
        }
        if (count == 0) throw new IllegalStateException("SZSE monthly dividend table matched 0 rows");
    }

    private RawNewsItem marginItem(NewsSource source, String provider, String category, String date,
                                   String code, String name, JsonNode row, List<String> fields, String pageUrl) {
        String exchange = "SSE".equals(provider) ? "上交所" : "深交所";
        String subject = code.isBlank() ? exchange : name + "（" + code + "）";
        String title = subject + category + " " + displayDate(date);
        Map<String, Object> metadata = baseMetadata(source, provider,
            "SSE".equals(provider) ? "sse-margin-api" : "szse-margin-report-api", category);
        metadata.put("tradeDate", displayDate(date));
        if (!code.isBlank()) metadata.put("securityCode", code);
        if (!name.isBlank()) metadata.put("securityName", name);
        fields.forEach(field -> put(metadata, field, row.path(field)));
        addNormalizedMargin(metadata, provider, category, row);
        String content = title + "；" + joinFields(row, fields) + "。";
        return item(source, title, content, pageUrl + "#" + category + "-" + code + "-" + date,
            date, category, code, name, metadata);
    }

    private RawNewsItem item(NewsSource source, String title, String content, String url, String date,
                             String category, String code, String name, Map<String, Object> metadata) {
        List<String> tags = new ArrayList<>(List.of("官方数据", "法律声明"));
        if (!code.isBlank()) tags.add(code);
        if (!name.isBlank()) tags.add(name);
        return new RawNewsItem(source, title, content, null,
            stringConfig(source, "providerName", source.name()), url, parseDate(date, source),
            stringConfig(source, "language", "zh-CN"), List.of(category), List.copyOf(tags), Map.copyOf(metadata));
    }

    private Map<String, Object> baseMetadata(NewsSource source, String provider, String transport, String dataset) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", transport);
        metadata.put("provider", provider);
        metadata.put("dataset", dataset);
        metadata.put("legalRisk", booleanConfig(source, "legalRisk", false));
        metadata.put("legalDisclaimer", stringConfig(source, "legalDisclaimer",
            "数据来自证券交易所官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；请以交易所最新披露为准。"));
        return metadata;
    }

    private void accept(NewsSource source, Counters counters, RawNewsItem item) {
        if (full(counters)) return;
        counters.discovered++;
        NewsAcceptance acceptance = sink.accept(item);
        if (acceptance == NewsAcceptance.ACCEPTED) counters.accepted++;
        else if (acceptance == NewsAcceptance.DUPLICATE) counters.duplicate++;
        else counters.rejected++;
    }

    private boolean full(Counters counters) {
        return counters.discovered >= properties.getMaxItemsPerRun();
    }

    private JsonNode tab(JsonNode root, String tabKey, String label) {
        if (root.isArray()) for (JsonNode node : root) if (tabKey.equals(node.path("metadata").path("tabkey").asText())) return node;
        throw new IllegalStateException(label + " has no " + tabKey);
    }

    private JsonNode array(JsonNode root, String field, String label) {
        JsonNode result = root.path(field);
        if (!result.isArray()) throw new IllegalStateException(label + " response has no " + field + " array");
        return result;
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private String get(String url, NewsSource source, Charset charset) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
            .header("Accept", "application/json,text/html,*/*")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return new String(response.body(), charset);
    }

    private String withQuery(String base, Map<String, String> params) {
        StringBuilder result = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> entry : params.entrySet()) {
            result.append(separator).append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            separator = '&';
        }
        return result.toString();
    }

    private String stripLoading(String url) {
        return url.replace("&loading=first", "").replace("?loading=first&", "?");
    }

    private String joinFields(JsonNode row, List<String> fields) {
        List<String> values = new ArrayList<>();
        for (String field : fields) if (!row.path(field).isMissingNode() && !row.path(field).asText("").isBlank()) {
            values.add(field + "=" + row.path(field).asText());
        }
        return String.join("，", values);
    }

    private void put(Map<String, Object> metadata, String field, JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return;
        if (value.isIntegralNumber()) metadata.put(field, value.longValue());
        else if (value.isFloatingPointNumber()) metadata.put(field, value.decimalValue());
        else metadata.put(field, value.asText());
    }

    private void copy(Map<String, Object> metadata, JsonNode row, String sourceField, String targetField) {
        put(metadata, targetField, row.path(sourceField));
    }

    private void addNormalizedMargin(Map<String, Object> metadata, String provider, String category, JsonNode row) {
        if ("SSE".equals(provider)) {
            copy(metadata, row, "rzye", "financingBalanceCny");
            copy(metadata, row, "rzmre", "financingBuyCny");
            copy(metadata, row, "rzche", "financingRepaymentCny");
            copy(metadata, row, "rqyl", "securitiesLendingOutstandingQuantity");
            copy(metadata, row, "rqylje", "securitiesLendingBalanceCny");
            copy(metadata, row, "rqmcl", "securitiesLendingSellQuantity");
            copy(metadata, row, "rqchl", "securitiesLendingRepaymentQuantity");
            copy(metadata, row, "rzrqjyzl", "marginTradingBalanceCny");
            metadata.put("amountUnit", "CNY");
            metadata.put("quantityUnit", "share/fund-unit");
            return;
        }
        copy(metadata, row, "jrrzmr", "financingBuy100MCny");
        copy(metadata, row, "jrrzye", "financingBalance100MCny");
        copy(metadata, row, "jrrzrjye", "marginTradingBalance100MCny");
        if ("融资融券汇总".equals(category)) {
            copy(metadata, row, "jrrjmc", "securitiesLendingSell100MQuantity");
            copy(metadata, row, "jrrjyl", "securitiesLendingOutstanding100MQuantity");
            copy(metadata, row, "jrrjye", "securitiesLendingBalance100MCny");
            metadata.put("quantityUnit", "100M-share/fund-unit");
        } else {
            copy(metadata, row, "jrrjmc", "securitiesLendingSell10KQuantity");
            copy(metadata, row, "jrrjyl", "securitiesLendingOutstanding10KQuantity");
            copy(metadata, row, "jrrjye", "securitiesLendingBalance10KCny");
            metadata.put("quantityUnit", "10K-share/fund-unit");
        }
        metadata.put("amountUnit", "100M-CNY; detail securities-lending balance uses 10K-CNY");
    }

    private Instant parseDate(String value, NewsSource source) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('/', '-');
        try {
            LocalDate date = normalized.matches("\\d{8}") ? LocalDate.parse(normalized, BASIC_DATE) : LocalDate.parse(normalized);
            return date.atStartOfDay(zone(source)).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String displayDate(String value) {
        if (value == null) return "";
        if (value.matches("\\d{8}")) return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6);
        return value.replace('/', '-');
    }

    private ZoneId zone(NewsSource source) {
        return ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

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

    private static final class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;
    }

    private static final class PageCursor {
        final String date;
        final int page;
        final boolean complete;

        private PageCursor(String date, int page, boolean complete) {
            this.date = date;
            this.page = page;
            this.complete = complete;
        }

        static PageCursor parse(String value) {
            if (value == null || value.isBlank() || !value.contains(":")) return null;
            String[] parts = value.split(":", 2);
            if ("complete".equals(parts[1])) return new PageCursor(parts[0], 1, true);
            try {
                return new PageCursor(parts[0], Math.max(1, Integer.parseInt(parts[1])), false);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
