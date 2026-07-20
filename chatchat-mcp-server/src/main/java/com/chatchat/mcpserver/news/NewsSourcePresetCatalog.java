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
            szseFundEtfAnnouncements(),
            szseMarginBusinessAnnouncements(),
            szseAuctionPublicInformation(),
            szseListingDisclosure(),
            eastmoneyFinance(),
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
                "CLS_TELEGRAPH", "https://www.cls.cn/telegraph", "cls.cn", "0 */2 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 2),
                    Map.entry("apiUrl", "https://www.cls.cn/api/cache"),
                    Map.entry("rollApiUrl", "https://www.cls.cn/v1/roll/get_roll_list"),
                    Map.entry("itemLimit", 20), Map.entry("maxPagesPerRun", 200),
                    Map.entry("initialBackfillHours", 24), Map.entry("sleepMillis", 300),
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
        return new Preset(code, name, "采集深交所要闻、深交所公告和上市公司公告及其二级正文或 PDF。",
            new SourceUpsert(code, name, "SZSE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("provider", "SZSE"),
                    Map.entry("newsSelector", ".homem-news-wrap .title a[href]"),
                    Map.entry("newsUrlContains", "/aboutus/trends/news/"), Map.entry("newsLimit", 10),
                    Map.entry("noticeIndexUrl", "https://www.szse.cn/disclosure/notice/index.json"),
                    Map.entry("noticeLimit", 20),
                    Map.entry("announcementApiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query"),
                    Map.entry("announcementStaticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("announcementLimit", 50), Map.entry("cninfoBaseUrl", "https://www.cninfo.com.cn"),
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
            Map.entry("presetVersion", 2),
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
                URI.create(url).getHost(), "0 */5 * * * *", false, configuration), null);
    }

    private Preset web(String code, String name, String url, String domain, String link, String title,
                       String content, String time, String author, String pattern, String cron) {
        return new Preset(code, name, "常用财经资讯网站预置；站点改版后请在后台调整选择器。",
            new SourceUpsert(code, name, "WEB_LIST", url, domain, cron,
                false, Map.of("sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")),
            new RuleUpsert(null, link, title, content, author, time, pattern));
    }

    public record Preset(String code, String name, String description,
                         SourceUpsert source, RuleUpsert rule) { }
    public record SourceUpsert(String sourceCode, String sourceName, String sourceType, String entryUrl,
                               String allowedDomain, String scheduleCron, Boolean enabled,
                               Map<String, Object> configuration) { }
    public record RuleUpsert(String listSelector, String linkSelector, String titleSelector,
                             String contentSelector, String authorSelector, String publishTimeSelector,
                             String urlPattern) { }
}
