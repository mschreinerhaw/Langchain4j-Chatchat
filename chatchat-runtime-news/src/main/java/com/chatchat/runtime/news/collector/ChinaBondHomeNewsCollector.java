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
import org.jsoup.parser.Parser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects the ChinaBond homepage yield curve, settlement statistics and research analysis. */
@Component
public class ChinaBondHomeNewsCollector implements NewsCollector {
    private static final String PROVIDER = "CHINABOND";
    private static final String AUTHOR = "中央国债登记结算有限责任公司";
    private static final Pattern ARTICLE_DATE = Pattern.compile("/t(\\d{8})_");
    private static final List<SettlementType> SETTLEMENT_TYPES = List.of(
        new SettlementType("320", "现券交易"),
        new SettlementType("hg_ywlx", "回购交易"),
        new SettlementType("341", "其中：质押式回购"),
        new SettlementType("331", "买断式回购"),
        new SettlementType("343", "远期交易"),
        new SettlementType("all_ywlx", "合计"));
    private static final List<OverviewMetric> MONTHLY_OVERVIEW_METRICS = List.of(
        new OverviewMetric("bond_issuance", "债券发行量"),
        new OverviewMetric("cash_bond_settlement", "现券交易结算量"),
        new OverviewMetric("repo_settlement", "回购交易结算量"),
        new OverviewMetric("bond_lending_settlement", "债券借贷结算量"),
        new OverviewMetric("bond_forward_settlement", "远期交易结算量"),
        new OverviewMetric("principal_payment", "债券兑付量"));
    private static final List<CollateralProduct> COLLATERAL_PRODUCTS = List.of(
        new CollateralProduct("fiscal_account_fund_management", "财政专户资金管理", 2, 3, 4),
        new CollateralProduct("foreign_currency_repo", "外币回购", 14, 15, 16),
        new CollateralProduct("bond_as_margin", "债券作为保证金", 5, 6, 7),
        new CollateralProduct("deposit_credit_pledge", "存款及授信质押类业务", 11, 12, 13),
        new CollateralProduct("cross_border_collateral", "跨境担保品", 17, 18, 19));

    private final NewsItemSink sink;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public ChinaBondHomeNewsCollector(NewsItemSink sink, ObjectMapper mapper) {
        this(sink, mapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ChinaBondHomeNewsCollector(NewsItemSink sink, ObjectMapper mapper, HttpClient client) {
        this.sink = sink;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.CHINABOND_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        Document home = null;
        try {
            home = Jsoup.parse(get(source.entryUrl(), source), source.entryUrl());
        } catch (Exception ex) {
            counters.failed++;
            errors.add("homepage: " + ex.getMessage());
        }
        try {
            collectOverview(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("overview: " + ex.getMessage());
        }
        try {
            collectYieldCurve(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("yield curve: " + ex.getMessage());
        }
        try {
            collectCounterQuotes(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("counter quotes: " + ex.getMessage());
        }
        try {
            collectSettlement(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("settlement: " + ex.getMessage());
        }
        try {
            collectCollateral(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("collateral: " + ex.getMessage());
        }
        if (home != null) {
            try {
                collectResearch(source, home, counters);
            } catch (Exception ex) {
                counters.failed++;
                errors.add("research: " + ex.getMessage());
            }
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed,
            errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void collectOverview(NewsSource source, Counters counters) throws Exception {
        JsonNode headline = mapper.readTree(get(config(source, "overviewHeadlineApiUrl",
            "https://www.chinabond.com.cn/ccdcdata/QueryZSGLForIndex_CN.json"), source));
        JsonNode monthly = mapper.readTree(get(config(source, "overviewMonthlyApiUrl",
            "https://www.chinabond.com.cn/ccdcdata/QueryIndexPageZSGLDataByMonth_CN.json"), source));
        List<Map<String, Object>> rows = new ArrayList<>();
        String period = "";
        if (headline.isArray()) {
            for (int index = 0; index < Math.min(2, headline.size()); index++) {
                JsonNode row = headline.path(index);
                period = first(row.path("tjny").asText(""), period);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("period", period);
                value.put("metricCode", index == 0 ? "bond_custody_balance" : "interbank_investor_count");
                value.put("metricName", row.path("xmmc").asText(index == 0 ? "债券托管量" : "银行间市场投资者数量"));
                value.put("metricNameEn", row.path("exmmc").asText(""));
                putNumber(value, "currentValue", row.path("slStr").asText(""));
                value.put("unit", index == 0 ? "100M-CNY" : "COUNT");
                value.put("metricGroup", "SUMMARY");
                rows.add(Map.copyOf(value));
            }
        }
        if (monthly.isArray()) {
            for (int index = 0; index < monthly.size(); index++) {
                JsonNode row = monthly.path(index);
                period = first(row.path("tjny").asText(""), period);
                OverviewMetric metric = index < MONTHLY_OVERVIEW_METRICS.size()
                    ? MONTHLY_OVERVIEW_METRICS.get(index) : new OverviewMetric("monthly_metric_" + index, row.path("xmmc").asText(""));
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("period", period);
                value.put("metricCode", metric.code());
                value.put("metricName", first(row.path("xmmc").asText(""), metric.name()));
                value.put("metricNameEn", row.path("exmmc").asText(""));
                putNumber(value, "currentMonthValue100MCny", row.path("bym").asText(""));
                putNumber(value, "yearToDateValue100MCny", row.path("bnlj").asText(""));
                value.put("unit", "100M-CNY");
                value.put("metricGroup", "MONTHLY_FLOW");
                rows.add(Map.copyOf(value));
            }
        }
        if (rows.isEmpty() || period.isBlank()) throw new IllegalStateException("ChinaBond overview response is empty");
        Map<String, Object> metadata = financialMetadata(source, "chinabond-overview-api",
            "中债统计概览", "bond_market_overview_monthly");
        metadata.put("period", period);
        metadata.put("overviewRows", List.copyOf(rows));
        metadata.put("snapshotMode", "OVERWRITE_BY_PERIOD_METRIC");
        accept(counters, new RawNewsItem(source, "中国债券信息网统计概览 " + period,
            "记录债券托管量、银行间投资者数量，以及发行、现券、回购、借贷、远期和兑付的月度及年度累计规模。",
            "中国债券市场月度统计概览。", AUTHOR, source.entryUrl() + "#overview-" + period,
            monthInstant(period, source), language(source), List.of("债券统计"),
            List.of("中债", "统计概览", "托管量", "发行量", "结算量"), Map.copyOf(metadata)));
    }

    private void collectYieldCurve(NewsSource source, Counters counters) throws Exception {
        String metadataUrl = config(source, "yieldMetadataUrl",
            "https://www.chinabond.com.cn/ccdcdata/yhj_data_xml_CN.xml");
        Document metadataXml = Jsoup.parse(get(metadataUrl, source), "", Parser.xmlParser());
        String pageDate = metadataXml.selectFirst("gxrq") == null ? "" : metadataXml.selectFirst("gxrq").text().trim();
        String chineseName = metadataXml.selectFirst("qxmc") == null ? "" : metadataXml.selectFirst("qxmc").text().trim();
        String api = config(source, "yieldApiUrl",
            "https://yield.chinabond.com.cn/cbweb-mn/yc/inityc?xyzSelect=txy&&workTime=&&dxbj=0&&qxll=0&&yqqxN=N&&yqqxK=K&&wrjxCBFlag=0");
        JsonNode root = mapper.readTree(post(api, source,
            config(source, "yieldReferer", "https://yield.chinabond.com.cn/cbweb-mn/yhj_chart")));
        JsonNode seriesList = root.path(1).path(1);
        if (!seriesList.isArray() || seriesList.isEmpty()) {
            throw new IllegalStateException("ChinaBond yield response has no curve series");
        }
        List<Map<String, Object>> points = new ArrayList<>();
        String latestDate = pageDate;
        String primaryName = chineseName;
        for (JsonNode series : seriesList) {
            String date = first(series.path("worktime").asText(""), pageDate);
            String curveName = first(chineseName, series.path("ycDefName").asText(""));
            String curveId = series.path("ycDefId").asText("");
            latestDate = first(date, latestDate);
            primaryName = first(curveName, primaryName);
            for (JsonNode point : series.path("seriesData")) {
                if (!point.isArray() || point.size() < 2 || !point.path(0).isNumber() || !point.path(1).isNumber()) continue;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("tradeDate", date);
                value.put("curveId", curveId);
                value.put("curveName", curveName);
                value.put("curveType", "YTM");
                value.put("maturityYears", point.path(0).decimalValue());
                value.put("yieldPct", point.path(1).decimalValue());
                points.add(Map.copyOf(value));
            }
        }
        if (points.isEmpty()) throw new IllegalStateException("ChinaBond yield response has no numeric points");
        Map<String, Object> metadata = financialMetadata(source, "chinabond-yield-api",
            "中债收益率曲线", "bond_yield_curve_daily");
        metadata.put("tradeDate", latestDate);
        metadata.put("curveName", primaryName);
        metadata.put("curvePoints", List.copyOf(points));
        metadata.put("snapshotMode", "OVERWRITE_BY_DATE_CURVE_MATURITY");
        String page = config(source, "yieldPageUrl", "https://yield.chinabond.com.cn/cbweb-mn/yield_main?locale=zh_CN");
        accept(counters, new RawNewsItem(source, primaryName + " " + latestDate,
            "中国债券信息网官方收益率曲线，业务日期 " + latestDate + "，共 " + points.size()
                + " 个期限点；同一业务日期、曲线和期限重复采集时覆盖更新。",
            "中债国债到期收益率完整期限曲线。", AUTHOR, page + "#curve-" + latestDate,
            instant(latestDate, source), language(source), List.of("债券收益率"),
            List.of("中债", "国债收益率", "收益率曲线", "官方数据"), Map.copyOf(metadata)));
    }

    private void collectSettlement(NewsSource source, Counters counters) throws Exception {
        String api = config(source, "settlementApiUrl",
            "https://www.chinabond.com.cn/ccdcdata/getRealtimeShtjfromtbl_CN.json");
        JsonNode root = mapper.readTree(get(api, source));
        // The official endpoint is reset after the settlement window and may temporarily expose only
        // [["all_ywlx","0.00","0.00","0","0.00"]]. Preserve the last valid intraday snapshot.
        if (root.isArray() && root.size() == 1 && root.path(0).isArray()) return;
        if (!root.isArray() || root.size() < 2) throw new IllegalStateException("ChinaBond settlement response is empty");
        String updatedAt = root.path(0).asText("").replaceAll("\\s+", " ").trim();
        String tradeDate = updatedAt.length() >= 10 ? updatedAt.substring(0, 10) : LocalDate.now(zone(source)).toString();
        Map<String, SettlementType> types = new LinkedHashMap<>();
        SETTLEMENT_TYPES.forEach(type -> types.put(type.code(), type));
        List<Map<String, Object>> settlements = new ArrayList<>();
        for (int index = 1; index < root.size(); index++) {
            JsonNode row = root.path(index);
            if (!row.isArray() || row.size() < 5) continue;
            SettlementType type = types.get(row.path(0).asText(""));
            if (type == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("tradeDate", tradeDate);
            value.put("settlementTime", updatedAt);
            value.put("settlementCode", type.code());
            value.put("settlementType", type.name());
            putNumber(value, "principalAmount100MCny", row.path(4).asText(""));
            putNumber(value, "faceAmount100MCny", row.path(1).asText(""));
            putNumber(value, "fundsAmount100MCny", row.path(2).asText(""));
            putNumber(value, "transactionCount", row.path(3).asText(""));
            value.put("amountUnit", "100M-CNY");
            settlements.add(Map.copyOf(value));
        }
        if (settlements.isEmpty()) throw new IllegalStateException("ChinaBond settlement response has no governed rows");
        Map<String, Object> metadata = financialMetadata(source, "chinabond-settlement-api",
            "中债结算情况", "bond_settlement_daily");
        metadata.put("tradeDate", tradeDate);
        metadata.put("settlementTime", updatedAt);
        metadata.put("settlements", List.copyOf(settlements));
        metadata.put("snapshotMode", "OVERWRITE_BY_DATE_SETTLEMENT_TYPE");
        String page = config(source, "settlementPageUrl", "https://www.chinabond.com.cn/zzsj/zzsj_jshq/");
        accept(counters, new RawNewsItem(source, "中国债券信息网结算情况 " + updatedAt,
            "现券、回购、质押式回购、买断式回购、远期交易及合计的本金、面值、资金金额和结算笔数，共 "
                + settlements.size() + " 类；同日同业务类型重复采集时覆盖更新。",
            "银行间债券市场实时结算规模。", AUTHOR, page + "#settlement-" + tradeDate,
            instant(tradeDate, source), language(source), List.of("债券结算"),
            List.of("中债", "结算", "现券", "回购", "官方数据"), Map.copyOf(metadata)));
    }

    private void collectCounterQuotes(NewsSource source, Counters counters) throws Exception {
        JsonNode root = mapper.readTree(get(config(source, "counterQuoteApiUrl",
            "https://www.chinabond.com.cn/ccdcdata/getIndexZybjInfo.json"), source));
        List<Map<String, Object>> quotes = new ArrayList<>();
        String tradeDate = "";
        if (root.isArray()) for (JsonNode row : root) {
            if (!row.isArray() || row.size() < 15) continue;
            tradeDate = first(row.path(8).asText(""), tradeDate);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("tradeDate", row.path(8).asText(""));
            value.put("bondCode", row.path(0).asText(""));
            value.put("bondName", row.path(1).asText(""));
            putNumber(value, "displayPrice", row.path(2).asText(""));
            value.put("remainingMaturityText", row.path(3).asText(""));
            putNumber(value, "displayYieldPct", row.path(4).asText("").replace("%", ""));
            value.put("bondType", row.path(5).asText(""));
            value.put("bondTypeEn", row.path(6).asText(""));
            putNumber(value, "bestBuyYieldPct", row.path(7).asText(""));
            value.put("bestBuyInstitutionCode", row.path(9).asText(""));
            value.put("bestBuyInstitution", row.path(10).asText("").trim());
            value.put("bestBuyInstitutionEn", row.path(11).asText("").trim());
            putNumber(value, "bestSellYieldPct", row.path(12).asText(""));
            value.put("bestSellInstitution", row.path(13).asText("").trim());
            value.put("bestSellInstitutionEn", row.path(14).asText("").trim());
            quotes.add(Map.copyOf(value));
        }
        if (quotes.isEmpty() || tradeDate.isBlank()) throw new IllegalStateException("ChinaBond counter quote response is empty");
        Map<String, Object> metadata = financialMetadata(source, "chinabond-counter-quote-api",
            "中债柜台行情", "bond_counter_quote_daily");
        metadata.put("tradeDate", tradeDate);
        metadata.put("counterQuotes", List.copyOf(quotes));
        metadata.put("snapshotMode", "OVERWRITE_BY_DATE_BOND");
        accept(counters, new RawNewsItem(source, "中国债券信息网柜台行情 " + tradeDate,
            "柜台业务关键期限债券最优报价，共 " + quotes.size() + " 只，保存债券、期限、报价收益率及报价机构。",
            "柜台债券关键期限最优报价行情。", AUTHOR, source.entryUrl() + "#counter-" + tradeDate,
            instant(tradeDate, source), language(source), List.of("债券柜台行情"),
            List.of("中债", "柜台行情", "最优报价", "到期收益率"), Map.copyOf(metadata)));
    }

    private void collectCollateral(NewsSource source, Counters counters) throws Exception {
        JsonNode root = mapper.readTree(get(config(source, "collateralApiUrl",
            "https://www.chinabond.com.cn/ccdcdata/queryIndexPageCounterData2_CN.json"), source));
        JsonNode row = root.path(0);
        if (!row.isArray() || row.size() < 21) throw new IllegalStateException("ChinaBond collateral response is empty");
        String period = row.path(20).asText("");
        List<Map<String, Object>> values = new ArrayList<>();
        Map<String, Object> balance = collateralValue(period, "managed_collateral_balance", "管理中担保品余额", "SUMMARY");
        putNumber(balance, "currentBalance100MCny", row.path(0).asText(""));
        balance.put("unit", "100M-CNY");
        values.add(Map.copyOf(balance));
        Map<String, Object> customers = collateralValue(period, "service_customer_count", "服务客户数量(含产品账户)", "SUMMARY");
        putNumber(customers, "customerCount", row.path(1).asText(""));
        customers.put("unit", "ACCOUNT");
        values.add(Map.copyOf(customers));
        for (CollateralProduct product : COLLATERAL_PRODUCTS) {
            Map<String, Object> value = collateralValue(period, product.code(), product.name(), "PRODUCT");
            putNumber(value, "previousMonthBalance100MCny", row.path(product.previous()).asText(""));
            putNumber(value, "currentMonthOperation100MCny", row.path(product.operation()).asText(""));
            putNumber(value, "currentMonthBalance100MCny", row.path(product.current()).asText(""));
            value.put("unit", "100M-CNY");
            values.add(Map.copyOf(value));
        }
        Map<String, Object> metadata = financialMetadata(source, "chinabond-collateral-api",
            "中债担保品信息", "bond_collateral_monthly");
        metadata.put("period", period);
        metadata.put("collateralRows", List.copyOf(values));
        metadata.put("snapshotMode", "OVERWRITE_BY_PERIOD_PRODUCT");
        accept(counters, new RawNewsItem(source, "中国债券信息网担保品信息 " + period,
            "记录管理中担保品余额、服务客户数量，以及五类重点担保品产品的上月余额、本月操作量和本月余额。",
            "中央结算公司担保品业务月度概览。", AUTHOR, source.entryUrl() + "#collateral-" + period,
            monthInstant(period, source), language(source), List.of("担保品"),
            List.of("中债", "担保品", "质押", "保证金", "跨境担保品"), Map.copyOf(metadata)));
    }

    private Map<String, Object> collateralValue(String period, String code, String name, String group) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("period", period);
        value.put("productCode", code);
        value.put("productName", name);
        value.put("metricGroup", group);
        return value;
    }

    private void collectResearch(NewsSource source, Document home, Counters counters) {
        String selector = config(source, "researchSelector", ".tabNewUl_data li");
        int limit = intConfig(source, "researchLimit", 10);
        int count = 0;
        for (Element item : home.select(selector)) {
            if (count >= limit) break;
            Element anchor = item.selectFirst(".tabNewContentWrap a[href], a[title][href]");
            if (anchor == null) continue;
            String url = anchor.absUrl("href");
            String title = first(anchor.attr("title"), anchor.text());
            if (url.isBlank() || title.isBlank()) continue;
            Set<String> attachments = new LinkedHashSet<>();
            item.select(".appendixs a[href]").forEach(link -> addUrl(attachments, link.absUrl("href")));
            String content = researchFallbackContent(title);
            try {
                pause(source);
                Document detail = Jsoup.parse(get(url, source), url);
                Element body = detail.selectFirst(config(source, "detailSelector",
                    ".TRS_Editor, .article-content, .content, .detail_content, #zoom, article, main"));
                if (body != null && !body.text().isBlank()) content = body.text().trim();
                detail.select("a[href$=.pdf],a[href*=.pdf?]").forEach(link -> addUrl(attachments, link.absUrl("href")));
            } catch (Exception ignored) {
                // The list title and official URL remain usable evidence when a detail page is temporarily unavailable.
            }
            if (content.length() < 80) content = content + "。" + researchFallbackContent(title);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("provider", PROVIDER);
            metadata.put("transport", "chinabond-research-home");
            metadata.put("section", "研究分析");
            metadata.put("legalRisk", booleanConfig(source, "legalRisk", false));
            metadata.put("legalDisclaimer", disclaimer(source));
            if (!attachments.isEmpty()) {
                metadata.put("attachmentUrls", List.copyOf(attachments));
                metadata.put("attachmentAllowedDomains", List.of("chinabond.com.cn"));
            }
            accept(counters, new RawNewsItem(source, title, content, title, AUTHOR, url,
                articleInstant(url, source), language(source), List.of("研究分析"),
                List.of("中国债券信息网", "债券研究", "官方报告"), Map.copyOf(metadata)));
            count++;
        }
        if (count == 0) throw new IllegalStateException("ChinaBond research selector matched no articles");
    }

    private Map<String, Object> financialMetadata(NewsSource source, String transport, String dataset,
                                                   String datasetCode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", PROVIDER);
        metadata.put("transport", transport);
        metadata.put("dataset", dataset);
        metadata.put("datasetCode", datasetCode);
        metadata.put("datasetName", dataset);
        metadata.put("legalRisk", booleanConfig(source, "legalRisk", false));
        metadata.put("legalDisclaimer", disclaimer(source));
        return metadata;
    }

    private String researchFallbackContent(String title) {
        return title + "。中国债券信息网研究分析栏目发布的官方债券市场研究材料，正文可能由附件承载，"
            + "报告内容、数据口径和发布日期以原始页面及附件为准。";
    }

    private void accept(Counters counters, RawNewsItem item) {
        counters.discovered++;
        NewsAcceptance acceptance = sink.accept(item);
        if (acceptance == NewsAcceptance.ACCEPTED) counters.accepted++;
        else if (acceptance == NewsAcceptance.DUPLICATE) counters.duplicate++;
        else counters.rejected++;
    }

    private String get(String url, NewsSource source) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), source, source.entryUrl());
    }

    private String post(String url, NewsSource source, String referer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()), source, referer);
    }

    private String send(HttpRequest.Builder builder, NewsSource source, String referer) throws Exception {
        HttpRequest request = builder.timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
            .header("Accept", "application/json,text/xml,text/html,*/*")
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "Mozilla/5.0 ChatChat-ChinaBondCollector/1.0").build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + request.uri());
        }
        return response.body();
    }

    private void pause(NewsSource source) throws InterruptedException {
        int millis = Math.max(0, intConfig(source, "sleepMillis", 200));
        if (millis > 0) Thread.sleep(millis);
    }

    private void putNumber(Map<String, Object> target, String field, String raw) {
        String value = raw == null ? "" : raw.replace(",", "").trim();
        if (!value.matches("[-+]?[0-9]+([.]\\d+)?")) return;
        target.put(field, new BigDecimal(value));
    }

    private void addUrl(Set<String> urls, String value) {
        if (value != null && !value.isBlank()) urls.add(value);
    }

    private Instant articleInstant(String url, NewsSource source) {
        Matcher matcher = ARTICLE_DATE.matcher(url);
        if (matcher.find()) {
            String value = matcher.group(1);
            try {
                return LocalDate.of(Integer.parseInt(value.substring(0, 4)), Integer.parseInt(value.substring(4, 6)),
                    Integer.parseInt(value.substring(6, 8))).atStartOfDay(zone(source)).toInstant();
            } catch (Exception ignored) { }
        }
        return Instant.now();
    }

    private Instant instant(String date, NewsSource source) {
        try { return LocalDate.parse(date).atStartOfDay(zone(source)).toInstant(); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private Instant monthInstant(String period, NewsSource source) {
        try { return YearMonth.parse(period, java.time.format.DateTimeFormatter.ofPattern("yyyyMM"))
            .atEndOfMonth().atStartOfDay(zone(source)).toInstant(); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private ZoneId zone(NewsSource source) { return ZoneId.of(config(source, "zoneId", "Asia/Shanghai")); }
    private String language(NewsSource source) { return config(source, "language", "zh-CN"); }
    private String disclaimer(NewsSource source) {
        return config(source, "legalDisclaimer", "数据及报告权益归中央国债登记结算有限责任公司及相关权利人所有；"
            + "仅用于内部市场研究和资讯检索，不得未经授权再分发或用于商业数据产品，不构成投资建议。");
    }
    private String config(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
    private boolean booleanConfig(NewsSource source, String key, boolean fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }
    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private record SettlementType(String code, String name) { }
    private record OverviewMetric(String code, String name) { }
    private record CollateralProduct(String code, String name, int previous, int operation, int current) { }
    private static final class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;
    }
}
