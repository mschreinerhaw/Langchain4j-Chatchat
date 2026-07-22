package com.chatchat.mcpserver.news;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class NewsSourcePresetCatalog {
    static final String SZSE_ENTRY_URL = "https://www.szse.cn/disclosure/listed/notice/";

    public List<Preset> presets() {
        return List.of(
            sseHome(),
            szseHome(),
            hkexHome(),
            csindexHome(),
            chinaBondHome(),
            sseDailySnapshot(),
            szseDailySnapshot(),
            sseMarketData(),
            szseMarketData(),
            sseEtfScale(),
            szseEtfScale(),
            threeMarketOverview(),
            szseFundEtfAnnouncements(),
            szseMarginBusinessAnnouncements(),
            szseAuctionPublicInformation(),
            szseListingDisclosure(),
            eastmoneyFinance(),
            eastmoney724(),
            stcnQuickNews(),
            stcnDisclosuresToday(),
            jin10MarketFlash(),
            jin10ImportantEvents(),
            clsTelegraph(),
            sseAnnouncements(),
            sseListingAnnouncements(),
            sseTradingSuspensionAnnouncements(),
            sseGeneralAnnouncements(),
            sseIntradaySuspension(),
            sseMarginAnnouncements(),
            sseFundAnnouncements(),
            sseBondAnnouncements(),
            sseOptionAnnouncements(),
            sseIpoLatest(),
            web("szse_announcements", "深圳证券交易所公告", SZSE_ENTRY_URL, "szse.cn",
                "a[href*='/disclosure/'], a[href$='.pdf']", "h1, .title", ".article-content, .content, body", ".date, .time", ".source",
                "https?://[^/]*szse\\.cn/.*", "0 */15 * * * *"),
            cninfoHome(),
            cninfoAnnouncements(),
            cninfoLatestSections()
        );
    }

    private Preset cninfoHome() {
        String code = "cninfo_home";
        String name = "巨潮资讯首页";
        String url = "https://www.cninfo.com.cn/new/index";
        return new Preset(code, name, "采集巨潮首页最新公告、互动问答、调研信息、网络投票和公开信息。",
            new SourceUpsert(code, name, "CNINFO_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 2),
                    Map.entry("provider", "CNINFO"),
                    Map.entry("sectionItemLimit", 20),
                    Map.entry("announcementColumns", "szse,sse"),
                    Map.entry("announcementApiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query"),
                    Map.entry("staticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("answersUrl", "https://www.cninfo.com.cn/new/companyReplies/getAnswersList"),
                    Map.entry("researchUrl", "https://www.cninfo.com.cn/new/index/researchInformation"),
                    Map.entry("votingUrl", "https://www.cninfo.com.cn/new/votingCompany/getMeetings"),
                    Map.entry("publicInfoUrl", "https://www.cninfo.com.cn/data/centerSpecial/getIndexPublicInfo?market="),
                    Map.entry("sleepMillis", 1000), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset clsTelegraph() {
        return new Preset("cls_telegraph", "财联社电报", "通过财联社页面使用的 JSON 接口翻页采集，并用持久化游标断点续采。",
            new SourceUpsert("cls_telegraph", "财联社电报",
                "STRUCTURED_FLASH", "https://www.cls.cn/telegraph", "cls.cn", "0 */2 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 3),
                    Map.entry("request", Map.of(
                        "url", "https://www.cls.cn/v1/roll/get_roll_list",
                        "signer", "CLS_WEB",
                        "query", Map.of("refresh_type", "1", "rn", "${pageSize}", "last_time", "${cursor}"))),
                    Map.entry("response", Map.of("successPath", "errno", "successValue", "0",
                        "errorMessagePath", "msg", "itemsPath", "data.roll_data")),
                    Map.entry("mapping", Map.ofEntries(
                        Map.entry("id", "id"), Map.entry("cursor", "ctime"),
                        Map.entry("title", List.of("title", "brief", "content")),
                        Map.entry("content", List.of("content", "brief", "title")),
                        Map.entry("summary", "brief"), Map.entry("author", "author"),
                        Map.entry("defaultAuthor", "财联社"), Map.entry("publishTime", "ctime"),
                        Map.entry("publishTimeFormat", "EPOCH_SECONDS"),
                        Map.entry("sourceUrl", "https://www.cls.cn/detail/${id}"),
                        Map.entry("tagPaths", List.of("subjects.subject_name")),
                        Map.entry("metadata", Map.of("telegraphId", "id", "level", "level",
                            "readingCount", "reading_num")),
                        Map.entry("staticMetadata", Map.of("provider", "CLS")))),
                    Map.entry("compliance", Map.of("categories", List.of("财联社电报"),
                        "tags", List.of("财联社"), "legalRisk", true,
                        "legalDisclaimer", "内容版权归财联社及原作者所有，仅用于内部资讯分析，不构成投资建议；使用前请确认符合网站条款及适用法律。")),
                    Map.entry("itemLimit", 20), Map.entry("maxPagesPerRun", 200),
                    Map.entry("initialBackfillHours", 24), Map.entry("sleepMillis", 300),
                    Map.entry("initialCursor", "0"), Map.entry("numericCursorBoundary", true),
                    Map.entry("minimumContentChars", 1),
                    Map.entry("legalRisk", true),
                    Map.entry("timeoutMillis", 20000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"))), null);
    }

    private Preset cninfoAnnouncements() {
        return new Preset("cninfo_announcements", "巨潮资讯公告",
            "通过巨潮资讯深市重要公告页的结构化接口采集当前最新重要公告，并异步解析公告 PDF 原文。",
            new SourceUpsert("cninfo_announcements", "巨潮资讯公告", "CNINFO_ANNOUNCEMENTS",
                "https://www.cninfo.com.cn/new/commonUrl?url=disclosure/list/notice#szse%2Fimportant",
                "cninfo.com.cn", "0 */10 * * * *", false,
                Map.ofEntries(
                    Map.entry("apiUrl", "https://www.cninfo.com.cn/new/disclosure"),
                    Map.entry("staticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("column", "szse_latest"), Map.entry("importantOnly", true),
                    Map.entry("itemLimit", 100),
                    Map.entry("sleepMillis", 1500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset szseHome() {
        String code = "szse_home";
        String name = "深圳证券交易所首页";
        String url = "https://www.szse.cn/index/index.html";
        return new Preset(code, name, "采集深交所要闻、深交所公告、上市公司公告及其二级正文或 PDF，并生成深证成指、创业板指、深证100和创业板50行情快照。",
            new SourceUpsert(code, name, "SZSE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 4), Map.entry("provider", "SZSE"),
                    Map.entry("newsSelector", ".homem-news-wrap .title a[href]"),
                    Map.entry("newsUrlContains", "/aboutus/trends/news/"), Map.entry("newsLimit", 10),
                    Map.entry("noticeIndexUrl", "https://www.szse.cn/disclosure/notice/index.json"),
                    Map.entry("noticeLimit", 20),
                    Map.entry("announcementApiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query"),
                    Map.entry("announcementStaticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("announcementLimit", 50), Map.entry("cninfoBaseUrl", "https://www.cninfo.com.cn"),
                    Map.entry("marketUrlTemplate", "https://www.szse.cn/api/market/ssjjhq/getTimeData?marketId=1&code={code}"),
                    Map.entry("marketCodes", List.of("399001", "399006", "399330", "399673")),
                    Map.entry("detailSelector", ".article-body,.news-detail-con,.des-content,.article-content,.text-content,.content,article,.g-content"),
                    Map.entry("sleepMillis", 1000), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset sseAnnouncements() {
        return new Preset("sse_announcements", "上海证券交易所公告",
            "采集上交所上市公司信息最新一页，并异步解析二级 PDF 公告全文。",
            new SourceUpsert("sse_announcements", "上海证券交易所公告", "SSE_ANNOUNCEMENTS",
                "https://www.sse.com.cn/disclosure/listedinfo/announcement/",
                "sse.com.cn", "0 */10 * * * *", false,
                Map.of("presetVersion", 3, "itemLimit", 100,
                    "feeds", List.of(feed(
                        "https://www.sse.com.cn/disclosure/listedinfo/announcement/json/stock_bulletin_publish_order.json",
                        "上市公司最新公告")),
                    "sleepMillis", 1000, "timeoutMillis", 20000,
                    "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset hkexHome() {
        String code = "hkex_home";
        String name = "香港交易所首页";
        String url = "https://www.hkex.com.hk/?sc_lang=zh-HK";
        return new Preset(code, name,
            "采集香港交易所官方新闻稿、监管公告、市场通讯，以及港交所披露易当日最新上市公司公告（最新 100 条），并保留 PDF 或 HTML 原文链接。",
            new SourceUpsert(code, name, "HKEX_HOME", url, "hkex.com.hk", "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "HKEX"),
                    Map.entry("rssFeeds", List.of(
                        Map.of("category", "港交所新闻稿", "url", "https://www.hkex.com.hk/Services/RSS-Feeds/News-Releases?sc_lang=zh-HK"),
                        Map.of("category", "港交所监管公告", "url", "https://www.hkex.com.hk/Services/RSS-Feeds/regulatory-announcements?sc_lang=zh-HK"),
                        Map.of("category", "港交所市场通讯", "url", "https://www.hkex.com.hk/Services/RSS-Feeds/market-communications?sc_lang=zh-HK"))),
                    Map.entry("rssItemLimit", 20),
                    Map.entry("disclosureUrl", "https://www1.hkexnews.hk/ncms/json/eds/lcisehk1relsdc_1.json"),
                    Map.entry("disclosureBaseUrl", "https://www1.hkexnews.hk"),
                    Map.entry("disclosureItemLimit", 100),
                    Map.entry("attachmentAllowedDomains", List.of("hkex.com.hk", "hkexnews.hk")),
                    Map.entry("sleepMillis", 500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Hong_Kong"), Map.entry("language", "zh-HK"),
                    Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", "内容来自香港交易所及披露易官方公开页面，仅用于资讯检索，不构成投资建议。"))), null);
    }

    private Preset csindexHome() {
        String code = "csindex_home";
        String name = "中证指数首页指数图";
        String url = "https://www.csindex.com.cn/zh-CN/downloads/index-information#/";
        String disclaimer = "数据及相关指数、商标权益归中证指数有限公司及相关权利人所有；仅用于内部资讯检索和研究，"
            + "不得未经授权再分发或用于商业用途，不构成投资建议。";
        return new Preset(code, name,
            "采集中证指数页面顶部沪深300、上证指数、科创综指、上证50、科创50五个指数的更新日期、收盘点位、涨跌幅、成交额，"
                + "以及页面指数图对应的历史收盘和滚动市盈率序列。",
            new SourceUpsert(code, name, "CSINDEX_HOME", url, "csindex.com.cn", "0 */10 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("provider", "CSINDEX"),
                    Map.entry("indexCodes", List.of("000300", "000001", "000680", "000016", "000688")),
                    Map.entry("indexSeriesUrl", "https://www.csindex.com.cn/csindex-home/homePage/indexMainAll"),
                    Map.entry("peHistoryUrlTemplate", "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe?indexCode={code}&startDate={startDate}&endDate={endDate}"),
                    Map.entry("sleepMillis", 300), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", true),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset chinaBondHome() {
        String code = "chinabond_home";
        String name = "中国债券信息网数据分析";
        String url = "https://www.chinabond.com.cn/";
        String disclaimer = "数据及报告权益归中央国债登记结算有限责任公司及相关权利人所有；仅用于内部市场研究和资讯检索，"
            + "不得未经授权再分发或用于商业数据产品，不构成投资建议。";
        return new Preset(code, name,
            "采集中债统计概览、完整收益率曲线、柜台行情、结算情况、担保品信息，以及首页研究分析报告和PDF。结构化数据按业务日期和业务键覆盖写。",
            new SourceUpsert(code, name, "CHINABOND_HOME", url, "chinabond.com.cn", "0 */10 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 4), Map.entry("provider", "CHINABOND"),
                    Map.entry("overviewHeadlineApiUrl", "https://www.chinabond.com.cn/ccdcdata/QueryZSGLForIndex_CN.json"),
                    Map.entry("overviewMonthlyApiUrl", "https://www.chinabond.com.cn/ccdcdata/QueryIndexPageZSGLDataByMonth_CN.json"),
                    Map.entry("yieldMetadataUrl", "https://www.chinabond.com.cn/ccdcdata/yhj_data_xml_CN.xml"),
                    Map.entry("yieldApiUrl", "https://yield.chinabond.com.cn/cbweb-mn/yc/inityc?xyzSelect=txy&&workTime=&&dxbj=0&&qxll=0&&yqqxN=N&&yqqxK=K&&wrjxCBFlag=0"),
                    Map.entry("yieldReferer", "https://yield.chinabond.com.cn/cbweb-mn/yhj_chart"),
                    Map.entry("yieldPageUrl", "https://yield.chinabond.com.cn/cbweb-mn/yield_main?locale=zh_CN"),
                    Map.entry("counterQuoteApiUrl", "https://www.chinabond.com.cn/ccdcdata/getIndexZybjInfo.json"),
                    Map.entry("settlementApiUrl", "https://www.chinabond.com.cn/ccdcdata/getRealtimeShtjfromtbl_CN.json"),
                    Map.entry("settlementPageUrl", "https://www.chinabond.com.cn/zzsj/zzsj_jshq/"),
                    Map.entry("collateralApiUrl", "https://www.chinabond.com.cn/ccdcdata/queryIndexPageCounterData2_CN.json"),
                    Map.entry("researchSelector", ".tabNewUl_data li"), Map.entry("researchLimit", 10),
                    Map.entry("detailSelector", ".TRS_Editor,.article-content,.content,.detail_content,#zoom,article,main"),
                    Map.entry("sleepMillis", 200), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"),
                    Map.entry("legalRisk", false), Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset sseDailySnapshot() {
        String code = "sse_daily_snapshot";
        String name = "上海证券交易所每日市场快照";
        String url = "https://www.sse.com.cn/market/view/";
        return new Preset(code, name,
            "采集上交所市场总貌、每日债券成交情况，以及股票、指数、基金和债券行情报表；同一交易日和业务键重复采集时覆盖更新。",
            new SourceUpsert(code, name, "EXCHANGE_DAILY_SNAPSHOT", url, "sse.com.cn", "0 */10 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "SSE"),
                    Map.entry("providerName", "上海证券交易所"),
                    Map.entry("sseQueryUrl", "https://query.sse.com.cn/commonQuery.do"),
                    Map.entry("bondPageUrl", "https://www.sse.com.cn/market/bonddata/overview/day/"),
                    Map.entry("quotePageUrl", "https://www.sse.com.cn/market/price/report/"),
                    Map.entry("quoteApiBaseUrl", "https://yunhq.sse.com.cn:32042/v1/sh1/list/exchange/"),
                    Map.entry("quoteCategories", List.of("equity", "index", "fwr", "bond")),
                    Map.entry("quotePageSize", 100), Map.entry("sleepMillis", 100),
                    Map.entry("timeoutMillis", 60000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", "数据来自上海证券交易所官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；请以上交所最新披露为准。"))), null);
    }

    private Preset szseDailySnapshot() {
        String code = "szse_daily_snapshot";
        String name = "深圳证券交易所每日市场快照";
        String url = "https://www.szse.cn/market/overview/index.html";
        return new Preset(code, name,
            "采集深交所市场总貌、股票/基金/债券每日概况，以及股票、基金、债券、回购、期权和指数行情；按游标分页，同一交易日和业务键重复采集时覆盖更新。",
            new SourceUpsert(code, name, "EXCHANGE_DAILY_SNAPSHOT", url, "szse.cn", "0 */10 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 3), Map.entry("provider", "SZSE"),
                    Map.entry("providerName", "深圳证券交易所"),
                    Map.entry("szseReportApiUrl", "https://www.szse.cn/api/report/ShowReport/data"),
                    Map.entry("quotePageUrl", "https://www.szse.cn/market/trend/index.html"),
                    Map.entry("lookbackDays", 10), Map.entry("snapshotPagesPerRun", 25),
                    Map.entry("sleepMillis", 500), Map.entry("requestRetries", 3), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"),
                    Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", "数据来自深圳证券交易所官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；请以深交所最新披露为准。"))), null);
    }

    private Preset sseMarketData() {
        String code = "sse_market_data";
        String name = "上海证券交易所市场表现数据";
        String url = "https://www.sse.com.cn/market/othersdata/margin/sum/";
        String disclaimer = "数据来自上海证券交易所官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；"
            + "融资融券数据以证券公司报送及交易所最新发布为准，使用和再分发前请确认符合网站条款及适用法律。";
        return new Preset(code, name,
            "采集上交所最近30个交易日融资融券汇总、最新交易日个股明细（当前第一页），以及当年现金分红和送股转增记录（当前第一页）。",
            new SourceUpsert(code, name, "EXCHANGE_MARKET_DATA", url, "sse.com.cn", "0 */30 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("provider", "SSE"),
                    Map.entry("providerName", "上海证券交易所"),
                    Map.entry("marginApiUrl", "https://query.sse.com.cn/commonSoaQuery.do"),
                    Map.entry("distributionApiUrl", "https://query.sse.com.cn/commonQuery.do"),
                    Map.entry("dividendPageUrl", "https://www.sse.com.cn/market/stockdata/dividends/dividend/"),
                    Map.entry("bonusPageUrl", "https://www.sse.com.cn/market/stockdata/dividends/bonus/"),
                    Map.entry("marginSummaryLimit", 30), Map.entry("marginDetailLimit", 100),
                    Map.entry("distributionLimit", 100), Map.entry("sleepMillis", 300),
                    Map.entry("timeoutMillis", 30000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset szseMarketData() {
        String code = "szse_market_data";
        String name = "深圳证券交易所市场表现数据";
        String url = "https://www.szse.cn/disclosure/margin/margin/index.html";
        String disclaimer = "数据来自深圳证券交易所官方公开页面和市场统计月报，仅用于内部市场研究和资讯检索，不构成投资建议；"
            + "融资融券数据以证券公司报送及交易所最新发布为准，使用和再分发前请确认符合网站条款及适用法律。";
        return new Preset(code, name,
            "采集深交所最新交易日融资融券交易总量和个股明细（前5页、约100只证券），以及最新一期市场统计月报中的分红、送股、配股、除净日和股权登记日。",
            new SourceUpsert(code, name, "EXCHANGE_MARKET_DATA", url, "szse.cn", "0 */30 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("provider", "SZSE"),
                    Map.entry("providerName", "深圳证券交易所"),
                    Map.entry("marginApiUrl", "https://www.szse.cn/api/report/ShowReport/data?SHOWTYPE=JSON&CATALOGID=1837_xxpl&loading=first"),
                    Map.entry("monthlyIndexUrl", "https://www.szse.cn/market/periodical/month/index.html"),
                    Map.entry("monthlyTableCharset", "GBK"), Map.entry("marginDetailPages", 5),
                    Map.entry("distributionLimit", 250), Map.entry("sleepMillis", 300),
                    Map.entry("timeoutMillis", 30000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset sseEtfScale() {
        String code = "sse_etf_scale";
        String name = "上海证券交易所ETF规模";
        String url = "https://www.sse.com.cn/market/funddata/volumn/etfvolumn/";
        String disclaimer = "数据来自上海证券交易所ETF规模官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；"
            + "页面规模口径为基金总份额（万份），请以上交所最新发布为准。";
        return new Preset(code, name,
            "采集上交所ETF规模页面最新规模日期的全部基金：基金代码、基金简称、ETF类型和总份额（万份）；超过单次上限时按交易日和页码断点续采。",
            new SourceUpsert(code, name, "EXCHANGE_MARKET_DATA", url, "sse.com.cn", "0 */30 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("mode", "FUND_SCALE"),
                    Map.entry("provider", "SSE"), Map.entry("providerName", "上海证券交易所"),
                    Map.entry("fundScaleApiUrl", "https://query.sse.com.cn/commonQuery.do"),
                    Map.entry("fundScalePageSize", 100), Map.entry("sleepMillis", 200),
                    Map.entry("timeoutMillis", 30000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset szseEtfScale() {
        String code = "szse_etf_scale";
        String name = "深圳证券交易所ETF规模";
        String url = "https://www.szse.cn/market/fund/volume/etf/index.html";
        String disclaimer = "数据来自深圳证券交易所ETF规模官方公开页面，仅用于内部市场研究和资讯检索，不构成投资建议；"
            + "页面规模口径为基金份额（万份），T日晚间数据仅供参考，以T+1日早间更新的T日规模为准。";
        return new Preset(code, name,
            "采集深交所ETF规模页面最近交易日的全部基金：基金代码、基金简称和当前规模（万份）；自动回看最近10日定位交易日，超过单次上限时断点续采。",
            new SourceUpsert(code, name, "EXCHANGE_MARKET_DATA", url, "szse.cn", "0 */30 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("mode", "FUND_SCALE"),
                    Map.entry("provider", "SZSE"), Map.entry("providerName", "深圳证券交易所"),
                    Map.entry("fundScaleApiUrl", "https://www.szse.cn/api/report/ShowReport/data?SHOWTYPE=JSON&CATALOGID=scsj_fund_jjgm&jjlb=ETF"),
                    Map.entry("fundScaleLookbackDays", 10), Map.entry("sleepMillis", 200),
                    Map.entry("timeoutMillis", 30000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset threeMarketOverview() {
        String code = "three_market_overview";
        String name = "沪深港市场汇总";
        String url = "https://www.hkex.com.hk/Mutual-Market/Stock-Connect/Statistics/Hong-Kong-and-Mainland-Market-Highlights?sc_lang=zh-HK";
        String disclaimer = "数据来自香港交易所公开的香港、上海和深圳市场概况，仅用于内部市场研究和资讯检索，"
            + "不构成投资建议；香港市场金额以港元计，沪深市场金额以人民币计，不同币种不得直接相加，"
            + "使用或再分发前请确认符合香港交易所网站条款及适用法律。";
        return new Preset(code, name,
            "采集同一参考交易日的香港、上海、深圳三个市场汇总：香港主板/创业板、上海A股/B股、深圳A股/B股；"
                + "包括上市公司数、H股及非H股内地企业数、上市证券数、总市值、流通市值、平均市盈率、成交股数、成交金额和市场总成交金额。",
            new SourceUpsert(code, name, "EXCHANGE_MARKET_DATA", url, "hkex.com.hk", "0 */30 * * * *", true,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("mode", "THREE_MARKET_OVERVIEW"),
                    Map.entry("provider", "HKEX"), Map.entry("providerName", "香港交易所市场概况"),
                    Map.entry("marketHighlightsApiUrl", "https://www.hkex.com.hk/chi/csm/ws/Highlightsearch.asmx/GetData"),
                    Map.entry("sleepMillis", 300), Map.entry("timeoutMillis", 30000), Map.entry("zoneId", "Asia/Hong_Kong"),
                    Map.entry("language", "zh-HK"), Map.entry("legalRisk", false),
                    Map.entry("legalDisclaimer", disclaimer))), null);
    }

    private Preset eastmoney724() {
        String disclaimer = "内容版权归东方财富及原作者所有，仅用于内部资讯分析，不构成投资建议；"
            + "使用前请确认已获得符合网站条款及适用法律的授权。";
        return new Preset("eastmoney_724", "东方财富全球财经资讯 7×24 小时直播",
            "通过东方财富快讯页面使用的 JSON 接口翻页采集，并用持久化游标断点续采；默认附加法律风险标签和使用声明。",
            new SourceUpsert("eastmoney_724", "东方财富全球财经资讯 7×24 小时直播",
                "STRUCTURED_FLASH", "https://kuaixun.eastmoney.com/", "eastmoney.com",
                "0 */2 * * * *", false, Map.ofEntries(
                    Map.entry("presetVersion", 2),
                    Map.entry("request", Map.of(
                        "url", "https://np-weblist.eastmoney.com/comm/web/getFastNewsList",
                        "signer", "NONE",
                        "query", Map.of("client", "web", "biz", "web_724", "fastColumn", "102",
                            "sortEnd", "${cursor}", "pageSize", "${pageSize}", "req_trace", "${timestamp}"))),
                    Map.entry("response", Map.of("successPath", "code", "successValue", "1",
                        "errorMessagePath", "message", "itemsPath", "data.fastNewsList",
                        "nextCursorPath", "data.sortEnd")),
                    Map.entry("mapping", Map.ofEntries(
                        Map.entry("id", "code"), Map.entry("cursor", "realSort"),
                        Map.entry("title", "title"), Map.entry("content", "summary"),
                        Map.entry("summary", "summary"), Map.entry("publishTime", "showTime"),
                        Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                        Map.entry("defaultAuthor", "东方财富"),
                        Map.entry("sourceUrl", "https://finance.eastmoney.com/a/${id}.html"),
                        Map.entry("metadata", Map.of("newsCode", "code", "realSort", "realSort",
                            "stockList", "stockList")),
                        Map.entry("staticMetadata", Map.of("provider", "EASTMONEY")))),
                    Map.entry("compliance", Map.of("categories", List.of("东方财富全球财经快讯"),
                        "tags", List.of("东方财富", "7×24快讯"), "legalRisk", true,
                        "legalDisclaimer", disclaimer)),
                    Map.entry("itemLimit", 50),
                    Map.entry("maxPagesPerRun", 200), Map.entry("initialBackfillHours", 24),
                    Map.entry("initialCursor", ""), Map.entry("numericCursorBoundary", true),
                    Map.entry("sleepMillis", 300), Map.entry("minimumContentChars", 1),
                    Map.entry("legalRisk", true), Map.entry("legalDisclaimer", disclaimer),
                    Map.entry("timeoutMillis", 20000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"))), null);
    }

    private Preset stcnQuickNews() {
        String disclaimer = "内容版权归证券时报、人民财讯及原作者所有，仅用于内部资讯分析，不构成投资建议；"
            + "使用前请确认已获得符合网站条款及适用法律的授权。";
        return new Preset("stcn_quick_news", "证券时报快讯",
            "通过证券时报快讯页面使用的 JSON 接口和复合分页状态断点采集，并保留来源与使用声明。",
            new SourceUpsert("stcn_quick_news", "证券时报快讯", "STRUCTURED_FLASH",
                "https://www.stcn.com/article/list/kx.html", "stcn.com", "0 */2 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1),
                    Map.entry("request", Map.of(
                        "url", "https://www.stcn.com/article/list.html",
                        "signer", "STCN_WEB",
                        "omitBlankQueryParameters", true,
                        "headers", Map.of("X-Requested-With", "XMLHttpRequest"),
                        "query", Map.of("type", "kx", "page_time", "${state.page}",
                            "last_time", "${state.cursor}"))),
                    Map.entry("response", Map.ofEntries(
                        Map.entry("successPath", "state"), Map.entry("successValue", "1"),
                        Map.entry("errorMessagePath", "msg"), Map.entry("itemsPath", "data"),
                        Map.entry("nextState", Map.of("page", "page_time", "cursor", "last_time")))),
                    Map.entry("mapping", Map.ofEntries(
                        Map.entry("id", "id"), Map.entry("cursor", "show_time"),
                        Map.entry("title", List.of("title", "content")), Map.entry("content", "content"),
                        Map.entry("summary", "share.description"), Map.entry("author", "source"),
                        Map.entry("defaultAuthor", "人民财讯"), Map.entry("publishTime", "time"),
                        Map.entry("publishTimeFormat", "EPOCH_MILLIS"),
                        Map.entry("sourceUrl", "${share_url}"), Map.entry("tagPaths", List.of("tags.name")),
                        Map.entry("metadata", Map.of("quickNewsId", "id", "isRed", "isRed",
                            "isTop", "isTop", "providerSource", "source")),
                        Map.entry("staticMetadata", Map.of("provider", "STCN", "channel", "人民财讯快讯")))),
                    Map.entry("compliance", Map.of("categories", List.of("证券时报快讯"),
                        "tags", List.of("证券时报", "人民财讯", "7×24快讯"), "legalRisk", false,
                        "legalDisclaimer", disclaimer)),
                    Map.entry("initialState", Map.of("page", "", "cursor", "")),
                    Map.entry("itemLimit", 30), Map.entry("maxPagesPerRun", 200),
                    Map.entry("initialBackfillHours", 24), Map.entry("numericCursorBoundary", true),
                    Map.entry("sleepMillis", 300), Map.entry("minimumContentChars", 1),
                    Map.entry("legalRisk", false), Map.entry("legalDisclaimer", disclaimer),
                    Map.entry("timeoutMillis", 20000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"))), null);
    }

    private Preset stcnDisclosuresToday() {
        String disclaimer = "本来源仅汇总证券时报信息披露平台当日第一页公告，应以指定信息披露媒体披露的公告全文为准；"
            + "内容版权归披露主体及原发布机构所有，不构成投资建议，使用前请确认符合网站条款及适用法律。";
        return new Preset("stcn_disclosures_today", "证券时报信息披露（当日第一页）",
            "采集截图所列全部板块当前第一页，严格保留采集当天发布的公告，并异步提取公告附件全文。",
            new SourceUpsert("stcn_disclosures_today", "证券时报信息披露（当日第一页）",
                "STCN_DISCLOSURES", "https://www.stcn.com/xinpi/list.html?type=sse", "stcn.com",
                "0 */10 * * * *", false, Map.ofEntries(
                    Map.entry("presetVersion", 1),
                    Map.entry("apiUrl", "https://www.stcn.com/xinpi/list-ajax.html"),
                    Map.entry("pageSize", 20),
                    Map.entry("sections", List.of(
                        Map.of("type", "sse", "name", "沪市主板"),
                        Map.of("type", "szse", "name", "深市主板"),
                        Map.of("type", "kcb", "name", "科创板"),
                        Map.of("type", "cyb", "name", "创业板"),
                        Map.of("type", "bse", "name", "北交所"),
                        Map.of("type", "xsb", "name", "新三板"),
                        Map.of("type", "hgt", "name", "沪股通"),
                        Map.of("type", "sgt", "name", "深股通"),
                        Map.of("type", "fund", "name", "基金"),
                        Map.of("type", "bond", "name", "债券"),
                        Map.of("type", "hk", "name", "港股"),
                        Map.of("type", "jg", "name", "监管"))),
                    Map.entry("attachmentAllowedDomains", List.of("stcn.com", "xp.stcn.com",
                        "neeq.com.cn", "sse.com.cn", "hkexnews.hk")),
                    Map.entry("legalRisk", false), Map.entry("legalDisclaimer", disclaimer),
                    Map.entry("minimumContentChars", 1), Map.entry("sleepMillis", 0),
                    Map.entry("timeoutMillis", 20000), Map.entry("zoneId", "Asia/Shanghai"),
                    Map.entry("language", "zh-CN"))), null);
    }

    private Preset jin10MarketFlash() {
        String disclaimer = "内容版权归金十数据及原作者所有，仅用于内部资讯分析，不构成投资建议；"
            + "不得绕过付费访问限制，使用前请确认已获得符合网站条款及适用法律的授权。";
        return new Preset("jin10_market_flash", "金十数据市场快讯",
            "通过金十数据首页使用的结构化快讯接口断点采集，自动跳过 PLUS 锁定占位项；默认附加法律风险标签和使用声明。",
            new SourceUpsert("jin10_market_flash", "金十数据市场快讯", "STRUCTURED_FLASH",
                "https://www.jin10.com/", "jin10.com", "0 */2 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1),
                    Map.entry("request", Map.of(
                        "url", "https://flash-api.jin10.com/get_flash_list",
                        "signer", "NONE", "omitBlankQueryParameters", true,
                        "headers", Map.of("Origin", "https://www.jin10.com",
                            "x-app-id", "SO1EJGmNgCtmpcPF", "x-version", "1.0.0"),
                        "query", Map.of("channel", "-8200", "max_time", "${state.cursor}"))),
                    Map.entry("response", Map.ofEntries(
                        Map.entry("successPath", "status"), Map.entry("successValue", "200"),
                        Map.entry("errorMessagePath", "message"), Map.entry("itemsPath", "data"),
                        Map.entry("nextStateFromLastItem", Map.of("cursor", "time")))),
                    Map.entry("mapping", Map.ofEntries(
                        Map.entry("id", "id"), Map.entry("cursor", "id"),
                        Map.entry("title", List.of("data.title", "data.content")),
                        Map.entry("content", List.of("data.content", "data.title")),
                        Map.entry("author", "data.source"), Map.entry("defaultAuthor", "金十数据"),
                        Map.entry("publishTime", "time"),
                        Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                        Map.entry("sourceUrl", "https://flash.jin10.com/detail/${id}"),
                        Map.entry("skipWhen", Map.of("data.lock", "true")),
                        Map.entry("metadata", Map.of("important", "important", "flashType", "type",
                            "channel", "channel", "sourceLink", "data.source_link", "remarks", "remark")),
                        Map.entry("staticMetadata", Map.of("provider", "JIN10", "channel", "市场快讯")))),
                    Map.entry("compliance", Map.of("categories", List.of("金十数据市场快讯"),
                        "tags", List.of("金十数据", "市场快讯", "7×24快讯"), "legalRisk", true,
                        "legalDisclaimer", disclaimer)),
                    Map.entry("initialState", Map.of("cursor", "")), Map.entry("itemLimit", 20),
                    Map.entry("maxPagesPerRun", 200), Map.entry("initialBackfillHours", 24),
                    Map.entry("numericCursorBoundary", true), Map.entry("sleepMillis", 300),
                    Map.entry("minimumContentChars", 1), Map.entry("legalRisk", true),
                    Map.entry("legalDisclaimer", disclaimer), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset jin10ImportantEvents() {
        String disclaimer = "内容版权归金十数据及原作者所有，仅用于内部重要事件分析，不构成投资建议；"
            + "不采集用户评论，不得绕过付费访问限制，使用前请确认已获得符合网站条款及适用法律的授权。";
        return new Preset("jin10_important_events", "金十数据重要事件",
            "完整扫描金十数据重要事件专题的编辑精选快照并依靠内容去重；默认附加法律风险标签和使用声明。",
            new SourceUpsert("jin10_important_events", "金十数据重要事件", "STRUCTURED_FLASH",
                "https://topic17z2k407.jin10.com/topic/jin10_important_news.html?from=web_homepage",
                "jin10.com", "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("snapshotMode", true),
                    Map.entry("request", Map.of(
                        "url", "https://1b8d6028d99849668a6d8755c79e650f.z3c.jin10.com/top/flashsByTime",
                        "signer", "NONE",
                        "headers", Map.of("Origin", "https://topic17z2k407.jin10.com",
                            "x-app-id", "EzF2s2HxxU0U5bYa", "x-version", "1.0.0"),
                        "query", Map.of("time_type", "time", "sort", "priority"))),
                    Map.entry("response", Map.of("successPath", "status", "successValue", "200",
                        "errorMessagePath", "message", "itemsPath", "data")),
                    Map.entry("mapping", Map.ofEntries(
                        Map.entry("id", List.of("item_id", "data.id")),
                        Map.entry("cursor", List.of("item_id", "data.id")),
                        Map.entry("title", List.of("data.data.title", "data.data.content")),
                        Map.entry("content", List.of("data.data.content", "data.data.title")),
                        Map.entry("author", "data.data.source"), Map.entry("defaultAuthor", "金十数据"),
                        Map.entry("publishTime", "data.time"),
                        Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                        Map.entry("sourceUrl", "https://flash.jin10.com/detail/${id}?from=important_news"),
                        Map.entry("tagPaths", List.of("data.data.tag")),
                        Map.entry("skipWhen", Map.of("data.data.lock", "true")),
                        Map.entry("metadata", Map.ofEntries(
                            Map.entry("important", "data.important"), Map.entry("eventType", "data.type"),
                            Map.entry("updatedAt", "updated_at"), Map.entry("channel", "data.channel"),
                            Map.entry("sourceLink", "data.data.source_link"),
                            Map.entry("articleLink", "data.data.link"), Map.entry("picture", "data.data.pic"),
                            Map.entry("remarks", "data.remark"))),
                        Map.entry("staticMetadata", Map.of("provider", "JIN10",
                            "channel", "重要事件", "snapshot", true)))),
                    Map.entry("compliance", Map.of("categories", List.of("金十数据重要事件"),
                        "tags", List.of("金十数据", "重要事件", "编辑精选"), "legalRisk", true,
                        "legalDisclaimer", disclaimer)),
                    Map.entry("itemLimit", 100), Map.entry("maxPagesPerRun", 1),
                    Map.entry("initialBackfillHours", 168), Map.entry("sleepMillis", 0),
                    Map.entry("minimumContentChars", 1), Map.entry("legalRisk", true),
                    Map.entry("legalDisclaimer", disclaimer), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset szseFundEtfAnnouncements() {
        String code = "szse_fund_etf_announcements";
        String name = "深交所基金及ETF公告";
        String url = "https://www.szse.cn/disclosure/fund/notice/index.html";
        return new Preset(code, name, "采集深交所基金公告和ETF公告当前最新一页，并解析二级官方PDF原文。",
            new SourceUpsert(code, name, "SZSE_DISCLOSURE", url, "szse.cn", "0 */10 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "SZSE"), Map.entry("itemLimit", 50),
                    Map.entry("feeds", List.of(
                        Map.of("kind", "FUND", "category", "基金公告",
                            "url", "https://www.szse.cn/api/disc/info/find/tannInfo?type=2&pageSize=50&pageNum=1",
                            "attachmentBaseUrl", "https://disc.static.szse.cn"),
                        Map.of("kind", "FUND", "category", "ETF公告",
                            "url", "https://www.szse.cn/api/disc/info/find/tannInfo?type=3&pageSize=50&pageNum=1",
                            "attachmentBaseUrl", "https://disc.static.szse.cn"))),
                    Map.entry("attachmentAllowedDomains", "disc.static.szse.cn"),
                    Map.entry("sleepMillis", 500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset szseMarginBusinessAnnouncements() {
        String code = "szse_margin_business_announcements";
        String name = "深交所融资融券业务公告";
        String url = "https://www.szse.cn/disclosure/margin/business/index.html";
        return new Preset(code, name, "采集融资融券业务公告当前一级列表，并进入二级文章提取正文及附件。",
            new SourceUpsert(code, name, "SZSE_DISCLOSURE", url, "szse.cn", "0 */15 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "SZSE"), Map.entry("itemLimit", 20),
                    Map.entry("feeds", List.of(Map.of("kind", "CMS_ARTICLES", "category", "融资融券业务公告", "url", url))),
                    Map.entry("sleepMillis", 800), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset szseAuctionPublicInformation() {
        String code = "szse_auction_public_information";
        String name = "深交所竞价交易公开信息";
        String url = "https://www.szse.cn/disclosure/deal/public/index.html";
        return new Preset(code, name, "采集竞价交易公开信息当前最新一页，并跟进二级买卖营业部交易明细。",
            new SourceUpsert(code, name, "SZSE_DISCLOSURE", url, "szse.cn", "0 */10 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "SZSE"), Map.entry("itemLimit", 10),
                    Map.entry("feeds", List.of(Map.of(
                        "kind", "AUCTION_REPORT", "category", "竞价交易公开信息",
                        "url", "https://www.szse.cn/api/report/ShowReport/data?SHOWTYPE=JSON&CATALOGID=1842_xxpl_after&PAGENO=1",
                        "detailBaseUrl", "https://www.szse.cn/api/report"))),
                    Map.entry("sleepMillis", 500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset szseListingDisclosure() {
        String code = "szse_listing_disclosure";
        String name = "深交所发行上市审核披露";
        String url = "https://www.szse.cn/listing/disclosure/ipo/index.html";
        String api = "https://www.szse.cn/api/ras/infodisc/query?pageIndex=0&pageSize=10&keywords=&disclosedStartDate=&disclosedEndDate=&catalog=&boardCode=&bizType=";
        return new Preset(code, name, "采集IPO、再融资、重大资产重组当前一级项目动态及其全部二级披露文件。",
            new SourceUpsert(code, name, "SZSE_DISCLOSURE", url, "szse.cn", "0 */15 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 1), Map.entry("provider", "SZSE"), Map.entry("itemLimit", 10),
                    Map.entry("feeds", List.of(
                        Map.of("kind", "RAS_PROJECTS", "category", "IPO审核信息披露", "url", api + "1",
                            "attachmentBaseUrl", "https://reportdocs.static.szse.cn"),
                        Map.of("kind", "RAS_PROJECTS", "category", "再融资审核信息披露", "url", api + "2",
                            "attachmentBaseUrl", "https://reportdocs.static.szse.cn"),
                        Map.of("kind", "RAS_PROJECTS", "category", "重大资产重组审核信息披露", "url", api + "3",
                            "attachmentBaseUrl", "https://reportdocs.static.szse.cn"))),
                    Map.entry("attachmentAllowedDomains", "reportdocs.static.szse.cn"),
                    Map.entry("sleepMillis", 500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset cninfoLatestSections() {
        return new Preset("cninfo_latest_sections", "巨潮资讯分板块最新公告",
            "采集巨潮最新公告页全部板块的当前最新一页，并异步解析二级 PDF 原文。",
            new SourceUpsert("cninfo_latest_sections", "巨潮资讯分板块最新公告", "CNINFO_ANNOUNCEMENTS",
                "https://www.cninfo.com.cn/new/commonUrl?url=disclosure/list/notice#szse",
                "cninfo.com.cn", "0 */15 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 3),
                    Map.entry("apiUrl", "https://www.cninfo.com.cn/new/disclosure"),
                    Map.entry("staticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("itemLimit", 30),
                    Map.entry("columns", List.of(
                        cninfoColumn("szse_latest", "深市公告", "深市"),
                        cninfoColumn("szse_main_latest", "深主板公告", "深主板"),
                        cninfoColumn("szse_gem_latest", "创业板公告", "创业板"),
                        cninfoColumn("sse_latest", "沪市公告", "沪市"),
                        cninfoColumn("sse_main_latest", "沪主板公告", "沪主板"),
                        cninfoColumn("sse_kcp_latest", "科创板公告", "科创板"),
                        cninfoColumn("bj_latest", "北交所公告", "北交所"),
                        cninfoColumn("fund_latest", "基金公告", "基金"),
                        cninfoColumn("bond_latest", "债券公告", "债券"),
                        cninfoColumn("hke_main_latest", "港股主板公告", "港股"),
                        cninfoColumn("hke_gem_latest", "港股创业板公告", "港股"),
                        cninfoColumn("szsh_relation", "调研公告", "调研"))),
                    Map.entry("sleepMillis", 1500), Map.entry("timeoutMillis", 30000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Map<String, Object> cninfoColumn(String column, String category, String section) {
        return Map.of("column", column, "category", category, "section", section);
    }

    private Preset sseListingAnnouncements() {
        return sseHtml("sse_listing_announcements", "上交所上市退市公告",
            "https://www.sse.com.cn/disclosure/announcement/listing/", "上市/退市公告");
    }

    private Preset sseGeneralAnnouncements() {
        return sseHtml("sse_general_announcements", "上交所一般公告",
            "https://www.sse.com.cn/disclosure/announcement/general/", "上交所一般公告");
    }

    private Preset sseMarginAnnouncements() {
        return sseHtml("sse_margin_announcements", "上交所融资融券公告",
            "https://www.sse.com.cn/disclosure/magin/announcement/", "融资融券公告");
    }

    private Preset sseOptionAnnouncements() {
        return sseHtml("sse_option_announcements", "上交所期权合约交易公告",
            "https://www.sse.com.cn/disclosure/optioninfo/update/", "合约与交易公告");
    }

    private Preset sseTradingSuspensionAnnouncements() {
        return sseFeeds("sse_trading_suspension_announcements", "上交所盘中停牌公告",
            "https://www.sse.com.cn/disclosure/announcement/tradingsuspension/", 25,
            List.of(Map.ofEntries(
                Map.entry("url", "https://query.sse.com.cn/commonSoaQuery.do?sqlId=SSE_PZLSTP_LBYCX&isPagination=true&pageHelp.pageSize=25&pageHelp.pageNo=1&pageHelp.beginPage=1&pageHelp.cacheSize=1&pageHelp.endPage=1"),
                Map.entry("itemsPath", "pageHelp.data"), Map.entry("category", "盘中停牌公告"),
                Map.entry("titleField", "title"), Map.entry("dateField", "publishDate"),
                Map.entry("codeField", "id"), Map.entry("nameField", "title"),
                Map.entry("urlTemplate", "/disclosure/announcement/tradingsuspension/index_article_detail.shtml?id={id}"))));
    }

    private Preset sseIntradaySuspension() {
        String base = "https://query.sse.com.cn/commonSoaQuery.do?sqlId=SSE_PZLSTP_ZW&isPagination=true&pageHelp.pageSize=200&pageHelp.pageNo=1&pageHelp.beginPage=1&pageHelp.cacheSize=1&pageHelp.endPage=1&tpType=";
        return sseFeeds("sse_intraday_suspension", "上交所盘中停牌信息",
            "https://www.sse.com.cn/disclosure/dealinstruc/intradaysuspension/", 200,
            List.of(
                Map.of("url", base + "1%2C3", "itemsPath", "result", "category", "主板盘中停牌信息",
                    "titleField", "title", "contentFields", List.of("issueNum", "content")),
                Map.of("url", base + "2", "itemsPath", "result", "category", "科创板盘中停牌信息",
                    "titleField", "title", "contentFields", List.of("issueNum", "content"))));
    }

    private Preset sseFundAnnouncements() {
        return sseFeeds("sse_fund_announcements", "上交所最新基金公告",
            "https://www.sse.com.cn/disclosure/fund/announcement/", 100,
            List.of(feed("https://www.sse.com.cn/disclosure/fund/announcement/json/fund_bulletin_publish_order.json",
                "最新基金公告")));
    }

    private Preset sseBondAnnouncements() {
        return sseFeeds("sse_bond_announcements", "上交所债券公告",
            "https://www.sse.com.cn/disclosure/bond/announcement/bookentry/", 100,
            List.of(
                feed("https://www.sse.com.cn/disclosure/bond/bookentry/announcement/json/bond_bulletin_publish_order.json", "国债公告"),
                feed("https://www.sse.com.cn/disclosure/bond/local/announcement/json/bond_bulletin_publish_order.json", "地方政府债券公告"),
                feed("https://www.sse.com.cn/disclosure/bond/common/announcement/json/bond_bulletin_publish_order.json", "金融债公告"),
                feed("https://www.sse.com.cn/disclosure/bond/announcement/company/json/bond_bulletin_publish_order.json", "公开发行公司债券公告"),
                feed("https://www.sse.com.cn/disclosure/bond/exchangeable/announcement/json/bond_bulletin_publish_order.json", "可交换公司债券公告")));
    }

    private Preset sseIpoLatest() {
        return sseFeeds("sse_ipo_latest", "上交所发行上市最新动态",
            "https://www.sse.com.cn/listing/renewal/ipo/", 25,
            List.of(Map.ofEntries(
                Map.entry("url", "https://query.sse.com.cn/commonSoaQuery.do?sqlId=SH_XM_LB&keyword=&issueMarketType=1%2C2&currStatus=&province=&csrcCode=&auditApplyDateBegin=&auditApplyDateEnd=&order=updateDate%7Cdesc%2CstockAuditNum%7Cdesc&isPagination=true&pageHelp.pageSize=25&pageHelp.pageNo=1&pageHelp.beginPage=1&pageHelp.cacheSize=1&pageHelp.endPage=1"),
                Map.entry("itemsPath", "pageHelp.data"), Map.entry("category", "发行上市最新动态"),
                Map.entry("titleField", "stockAuditName"), Map.entry("dateField", "updateDate"),
                Map.entry("codeField", "stockAuditNum"), Map.entry("nameField", "stockAuditName"),
                Map.entry("urlTemplate", "/listing/renewal/ipo/index_listing_detail.shtml?auditId={stockAuditNum}"),
                Map.entry("contentFields", List.of("issueMarketTypeDesc", "currStatusDesc", "province", "csrcDesc")))));
    }

    private Preset sseFeeds(String code, String name, String entryUrl, int itemLimit,
                            List<Map<String, Object>> feeds) {
        return new Preset(code, name, "采集上交所官方栏目最新一页，并保留二级正文或 PDF 原文链接。",
            new SourceUpsert(code, name, "SSE_ANNOUNCEMENTS", entryUrl, "sse.com.cn",
                "0 */10 * * * *", false,
                Map.of("presetVersion", 3, "itemLimit", itemLimit, "feeds", feeds,
                    "sleepMillis", 1000, "timeoutMillis", 30000,
                    "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Map<String, Object> feed(String url, String category) {
        return Map.of("url", url, "itemsPath", "publishData", "category", category,
            "titleField", "bulletinTitle", "dateField", "discloseDate",
            "codeField", "securityCode", "nameField", "securityAbbr", "urlField", "bulletinUrl");
    }

    private Preset sseHtml(String code, String name, String url, String category) {
        return new Preset(code, name, "采集上交所官方栏目最新一页及其二级正文或附件。",
            new SourceUpsert(code, name, "WEB_LIST", url, "sse.com.cn", "0 */10 * * * *", false,
                Map.of("presetVersion", 3, "itemLimit", 30, "category", category,
                    "sleepMillis", 1000, "timeoutMillis", 30000,
                    "zoneId", "Asia/Shanghai", "language", "zh-CN")),
            new RuleUpsert(null,
                "a[href*='/disclosure/'][href*='/c/'], a[href*='/listing/'][href*='/c/']",
                "h1, .article-title, .title", "article, .article-content, .allZoom, .content, .detail_content, .sse_content, main",
                ".source", ".date, .time, .article-infor", "https?://(?:[^/]+\\.)?sse\\.com\\.cn/.*"));
    }

    private Preset eastmoneyFinance() {
        String url = "https://finance.eastmoney.com/";
        return new Preset("eastmoney_finance", "东方财富财经",
            "采集东方财富财经首页的数字编号文章详情，排除栏目和导航页面。",
            new SourceUpsert("eastmoney_finance", "东方财富财经", "WEB_LIST", url, "eastmoney.com",
                "0 */10 * * * *", false,
                Map.ofEntries(Map.entry("presetVersion", 2), Map.entry("legalRisk", true),
                    Map.entry("sleepMillis", 1000), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))),
            new RuleUpsert(null, "a[href*='/a/'][href$='.html']", ".title",
                "#ContentBody", ".infos .item:nth-of-type(2)", ".infos .item:first-of-type",
                "https?://finance\\.eastmoney\\.com/a/\\d+\\.html(?:\\?.*)?"));
    }

    private Preset sseHome() {
        String url = "https://www.sse.com.cn/";
        Map<String, Object> configuration = Map.ofEntries(
            Map.entry("presetVersion", 3),
            Map.entry("provider", "SSE"),
            Map.entry("headlineSelector", ".hot_dyn a.dynaTitle"),
            Map.entry("headlineLimit", 12),
            Map.entry("sectionItemLimit", 20),
            Map.entry("sectionSelectors", Map.of(
                "要闻", ".sse2020_lev1_block:has(> h1:contains(要闻)) a.dynaTitle",
                "热点动态", ".js_news_more a.dynaTitle",
                "各栏更新", ".column_news a.column_newsTitle",
                "衍生品公告", ".Derivatives a",
                "近期上市", ".recentListing a"
            )),
            Map.entry("detailContentSelector", "article, .article-content, .allZoom, .content, .detail_content, .sse_content, main"),
            Map.entry("detailAllowedDomains", List.of("sse.com.cn", "gov.cn")),
            Map.entry("attachmentAllowedDomains", List.of("sse.com.cn", "static.sse.com.cn")),
            Map.entry("ipoIntroductionUrlTemplate", "https://query.sse.com.cn/aboutsse/dynamic/getIPOIntrByCompanyId.do?companyId={companyId}&jsonCallBack=callback"),
            Map.entry("ipoOverviewUrlTemplate", "https://query.sse.com.cn/commonSoaQuery.do?sqlId=GP_GPHQ_GPFXLB&tradeMarket=SH&token=APPMQUERY&stockCode={companyId}&jsonCallBack=callback"),
            Map.entry("announcementBaseUrl", "https://static.sse.com.cn"),
            Map.entry("announcementFeeds", List.of(
                Map.of("section", "上市公司公告", "url", url + "disclosure/listedinfo/announcement/json/stock_bulletin_publish_order.json"),
                Map.of("section", "债券公告", "url", url + "disclosure/bond/bookentry/announcement/json/bond_bulletin_publish_order.json"),
                Map.of("section", "基金公告", "url", url + "disclosure/fund/announcement/json/fund_bulletin_publish_order.json")
            )),
            Map.entry("marketDataFeeds", List.of(
                Map.of("section", "数据总貌", "url", url + "home/data_1998.js", "variable", "home_sjtj"),
                Map.of("section", "主板", "url", url + "home/data_1999.js", "variable", "home_sjtj_zb"),
                Map.of("section", "科创板", "url", url + "home/data_2000.js", "variable", "home_sjtj_kcb")
            )),
            Map.entry("marketUrlTemplate", "https://yunhq.sse.com.cn:32042/v1/sh1/snap/{code}"),
            Map.entry("marketCodes", List.of("000001", "000680", "000888", "000016", "000688", "000010", "000300")),
            Map.entry("sleepMillis", 1000),
            Map.entry("timeoutMillis", 20000),
            Map.entry("zoneId", "Asia/Shanghai"),
            Map.entry("language", "zh-CN")
        );
        return new Preset("sse_home", "上海证券交易所首页",
            "采集首页要闻、热点、栏目更新、指数、市场数据、各类公告和近期上市，并抓取二级详情或公告附件。",
            new SourceUpsert("sse_home", "上海证券交易所首页", "EXCHANGE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", true, configuration), null);
    }

    private Preset web(String code, String name, String url, String domain, String link, String title,
                       String content, String time, String author, String pattern, String cron) {
        return new Preset(code, name, "常用财经资讯网站预置；站点改版后请在后台调整选择器。",
            new SourceUpsert(code, name, "WEB_LIST", url, domain, cron,
                false, Map.of("sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")),
            new RuleUpsert(null, link, title, content, author, time, pattern));
    }

    public record Preset(String code, String name, String description,
                         SourceUpsert source, RuleUpsert rule) {
        public Preset {
            if (source != null && (source.collectionDescription() == null || source.collectionDescription().isBlank())) {
                source = source.withCollectionDescription(description);
            }
        }
    }
    public record SourceUpsert(String sourceCode, String sourceName, String sourceType, String entryUrl,
                               String collectionDescription,
                               String allowedDomain, String scheduleCron, Boolean enabled,
                               Map<String, Object> configuration) {
        public SourceUpsert(String sourceCode, String sourceName, String sourceType, String entryUrl,
                            String allowedDomain, String scheduleCron, Boolean enabled,
                            Map<String, Object> configuration) {
            this(sourceCode, sourceName, sourceType, entryUrl, null, allowedDomain, scheduleCron, enabled, configuration);
        }

        public SourceUpsert withCollectionDescription(String description) {
            return new SourceUpsert(sourceCode, sourceName, sourceType, entryUrl, description,
                allowedDomain, scheduleCron, enabled, configuration);
        }
    }
    public record RuleUpsert(String listSelector, String linkSelector, String titleSelector,
                             String contentSelector, String authorSelector, String publishTimeSelector,
                             String urlPattern) { }
}
