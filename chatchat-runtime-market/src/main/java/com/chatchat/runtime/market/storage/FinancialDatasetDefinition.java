package com.chatchat.runtime.market.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable business vocabulary placed in the asset catalog; physical columns may evolve independently. */
public record FinancialDatasetDefinition(
    String code, String tableName, String name, String description, List<String> keywords,
    String updateFrequency, Map<String, String> fieldDescriptions
) {
    private static final Map<String, FinancialDatasetDefinition> DEFINITIONS = definitions();

    public static FinancialDatasetDefinition from(Map<String, Object> metadata) {
        String explicit = text(metadata.get("datasetCode"));
        if (!explicit.isBlank()) return DEFINITIONS.getOrDefault(normalizeCode(explicit), dynamic(explicit, metadata));
        String dataset = text(metadata.get("dataset"));
        String provider = text(metadata.get("provider")).toUpperCase(Locale.ROOT);
        String transport = text(metadata.get("transport"));
        if (dataset.contains("融资融券")) return DEFINITIONS.get("margin_trade_daily");
        if (dataset.contains("分红") || dataset.contains("送股") || dataset.contains("配股")) {
            return DEFINITIONS.get("stock_dividend_event");
        }
        if (dataset.contains("ETF规模")) return DEFINITIONS.get("etf_scale_daily");
        if (dataset.contains("债券") && (dataset.contains("日度") || dataset.contains("成交"))) {
            return DEFINITIONS.get("bond_market_daily");
        }
        if (dataset.contains("市场汇总") || dataset.contains("市场统计") || "sse-market-data".equals(transport)) {
            return DEFINITIONS.get("market_statistics_daily");
        }
        if ("CSINDEX".equals(provider) && metadata.containsKey("indexCode")) {
            return DEFINITIONS.get("index_valuation_daily");
        }
        if (dataset.contains("估值")) return DEFINITIONS.get("stock_valuation_daily");
        if (dataset.contains("行情") || metadata.containsKey("quoteCode")) return DEFINITIONS.get("market_quote_daily");
        return null;
    }

    public static FinancialDatasetDefinition byCode(String code) {
        return code == null ? null : DEFINITIONS.get(normalizeCode(code));
    }

    private static FinancialDatasetDefinition dynamic(String raw, Map<String, Object> metadata) {
        String code = normalizeCode(raw);
        String name = text(metadata.get("datasetName"));
        String description = text(metadata.get("businessDescription"));
        return new FinancialDatasetDefinition(code, "fd_" + code,
            name.isBlank() ? code : name, description.isBlank() ? "通过受控金融数据API采集的结构化数据集：" + code : description,
            List.of(code, "金融数据"), "按来源调度", Map.of());
    }

    static String normalizeCode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            throw new IllegalArgumentException("Invalid financial dataset code: " + value);
        }
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private static Map<String, FinancialDatasetDefinition> definitions() {
        Map<String, FinancialDatasetDefinition> values = new LinkedHashMap<>();
        add(values, "market_quote_daily", "market_quote_daily", "证券及指数行情",
            "记录证券或指数的开盘、最高、最低、最新/收盘、涨跌幅、成交量和成交额，用于与资讯事件联合分析价格表现和交易活跃度。",
            List.of("行情", "股价", "指数", "涨跌", "成交量", "成交额"), fields(
                "trade_date", "交易日期", "security_code", "证券代码", "close", "收盘或最新价", "change_pct", "涨跌幅"));
        add(values, "stock_valuation_daily", "stock_valuation_daily", "证券估值",
            "记录证券市盈率、市净率、市销率及市值等估值指标，用于评估价格与基本面的相对水平。",
            List.of("估值", "市盈率", "市净率", "PE", "PB", "市值"), Map.of());
        add(values, "index_valuation_daily", "index_valuation_daily", "指数行情与估值",
            "记录主要指数每日收盘、涨跌幅、成交额和滚动市盈率，并保留官方历史序列。",
            List.of("指数", "估值", "滚动市盈率", "收盘", "成交额"), fields(
                "index_code", "指数代码", "close", "收盘点位", "pe_ttm_history", "滚动市盈率历史序列"));
        add(values, "margin_trade_daily", "margin_trade_daily", "融资融券每日数据",
            "记录交易所融资买入、融资余额、融券卖出及融券余量等汇总或个券数据，用于判断杠杆资金方向。",
            List.of("融资融券", "融资余额", "融券余量", "杠杆资金", "个券"), Map.of());
        add(values, "stock_dividend_event", "stock_dividend_event", "分红送配事件",
            "记录证券现金分红、送股、转增、配股、除权除息日和股权登记日等权益事件。",
            List.of("分红", "送股", "转增", "配股", "除权除息", "股权登记"), Map.of());
        add(values, "etf_scale_daily", "etf_scale_daily", "ETF规模每日数据",
            "记录沪深交易所ETF代码、名称、类型和基金份额规模，用于分析ETF资金规模变化。",
            List.of("ETF", "基金规模", "基金份额", "资金流向"), fields(
                "fund_code", "ETF代码", "fund_scale10_k_units", "基金规模或总份额（万份）"));
        add(values, "market_statistics_daily", "market_statistics_daily", "交易所市场统计",
            "记录沪深港市场分板块上市公司数、证券数、市值、市盈率、成交股数和成交金额等官方统计。",
            List.of("市场统计", "总市值", "流通市值", "平均市盈率", "成交金额", "上市公司数"), Map.of());
        add(values, "bond_market_daily", "bond_market_daily", "交易所每日债券成交情况",
            "记录沪深交易所各债券类别每日成交笔数、成交量、成交金额和加权平均价格等官方统计，用于分析债券市场活跃度与结构变化。",
            List.of("债券", "债券成交", "国债", "公司债", "可转债", "回购", "成交金额"), fields(
                "trade_date", "交易日期", "bond_category", "债券类别", "turnover_amount10_k_cny", "成交金额（万元）",
                "transaction_count", "成交笔数", "weighted_average_price", "加权平均价格"));
        add(values, "bond_market_overview_monthly", "bond_market_overview_monthly", "中债市场统计概览",
            "记录中国债券信息网债券托管量、银行间投资者数量，以及债券发行、现券、回购、借贷、远期和兑付的当月与年度累计规模。",
            List.of("中债", "统计概览", "债券托管量", "投资者数量", "发行量", "结算量"), fields(
                "period", "统计月份", "metric_code", "统计指标编码", "metric_name", "统计指标名称",
                "current_value", "月末值", "current_month_value100_m_cny", "本月金额（亿元）",
                "year_to_date_value100_m_cny", "本年累计金额（亿元）", "unit", "单位"));
        add(values, "bond_yield_curve_daily", "bond_yield_curve_daily", "中债收益率曲线每日数据",
            "记录中债国债到期收益率曲线的完整期限点，用于分析利率水平、期限结构、曲线斜率及债券市场估值变化。",
            List.of("中债", "国债收益率", "收益率曲线", "期限利差", "利率债"), fields(
                "trade_date", "曲线业务日期", "curve_name", "曲线名称", "maturity_years", "待偿期限（年）",
                "yield_pct", "到期收益率（%）", "curve_type", "收益率曲线类型"));
        add(values, "bond_counter_quote_daily", "bond_counter_quote_daily", "中债柜台债券行情",
            "记录中国债券信息网柜台业务关键期限债券的价格、剩余期限、最优买卖收益率和报价机构，用于分析柜台债券价格及流动性。",
            List.of("中债", "柜台行情", "柜台债券", "最优报价", "报价机构", "到期收益率"), fields(
                "trade_date", "报价日期", "bond_code", "债券代码", "bond_name", "债券简称",
                "remaining_maturity_text", "剩余期限", "best_buy_yield_pct", "最优买报价收益率（%）",
                "best_sell_yield_pct", "最优卖报价收益率（%）", "best_buy_institution", "最优买报价方",
                "best_sell_institution", "最优卖报价方"));
        add(values, "bond_settlement_daily", "bond_settlement_daily", "中债市场结算实时统计",
            "记录中国债券信息网现券、回购、质押式回购、买断式回购、远期交易及合计的本金、面值、资金和笔数，用于判断银行间债券市场活跃度与资金规模。",
            List.of("中债", "结算", "现券交易", "回购交易", "质押式回购", "买断式回购", "远期交易"), fields(
                "trade_date", "结算日期", "settlement_time", "页面更新时间", "settlement_type", "结算业务类型",
                "principal_amount100_m_cny", "本金金额（亿元）", "face_amount100_m_cny", "面值金额（亿元）",
                "funds_amount100_m_cny", "资金金额（亿元）", "transaction_count", "结算笔数"));
        add(values, "bond_collateral_monthly", "bond_collateral_monthly", "中债担保品业务月度数据",
            "记录中央结算公司管理中担保品余额、服务客户数量，以及财政专户、外币回购、保证金、存款授信质押和跨境担保品业务规模。",
            List.of("中债", "担保品", "财政专户", "外币回购", "保证金", "质押", "跨境担保品"), fields(
                "period", "统计月份", "product_code", "担保品业务编码", "product_name", "担保品业务名称",
                "previous_month_balance100_m_cny", "上月余额（亿元）", "current_month_operation100_m_cny", "本月操作量（亿元）",
                "current_month_balance100_m_cny", "本月余额（亿元）", "customer_count", "服务客户数量"));
        return Map.copyOf(values);
    }

    private static void add(Map<String, FinancialDatasetDefinition> target, String code, String table, String name,
                            String description, List<String> keywords, Map<String, String> fields) {
        target.put(code, new FinancialDatasetDefinition(code, table, name, description, keywords, "按交易日/采集调度", fields));
    }

    private static Map<String, String> fields(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(values[i], values[i + 1]);
        return Map.copyOf(result);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
