package com.chatchat.runtime.market.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete, user-owned examples for analysing the governed market-data tables.
 * Persistence is implemented by the host MCP server so this module remains free
 * of server registry dependencies.
 */
public final class FinancialAnalysisQuerySamples {

    public static final String INTERNAL_DATASOURCE_ID = "builtin_financial_market";
    public static final String BUSINESS_GROUP = "financial_market_examples";
    public static final String BUSINESS_GROUP_NAME = "金融数据分析样例";
    public static final String BUSINESS_GROUP_DESCRIPTION =
        "基于系统已采集的交易所行情、估值、融资融券、ETF及中债数据构建的只读分析参考。"
            + "样例默认停用，启用前应确认对应数据集已有采集记录和字段。";

    private FinancialAnalysisQuerySamples() {
    }

    public static List<Sample> all() {
        return List.of(
            sample(
                "builtin-market-dataset-freshness",
                "sample_financial_dataset_freshness",
                "金融数据资产覆盖与新鲜度",
                "查看各金融数据集的业务名称、更新频率、最近观测日期、最近采集时间、冷热分层保留策略和数据来源。"
                    + "适用于采集完整性检查、数据延迟排查，以及在正式分析前确认可用证据范围。",
                """
                1. 从金融资产目录读取所有已登记数据集。
                2. 按最近观测日期倒序排列，识别长期未更新或尚无观测的数据集。
                3. 结合更新频率、来源和保留策略判断数据是否适合当前分析。
                4. 本查询只反映数据可用性，不直接推断市场方向。
                """,
                """
                SELECT dataset_code, asset_name, business_description, update_frequency,
                       last_observation_date, last_collected_at, history_granularity,
                       hot_retention_days, archive_retention_days, source_names_json
                FROM market_asset_catalog
                ORDER BY last_observation_date DESC, dataset_code
                """,
                emptySchema(),
                List.of("金融数据", "数据质量", "采集状态", "新鲜度", "资产目录"),
                "financial_data_quality",
                100,
                "每行代表一个金融数据集；last_observation_date 是最新业务日期，last_collected_at 是系统最近入库时间。"
            ),
            sample(
                "builtin-market-latest-movers",
                "sample_market_latest_movers",
                "最新交易日证券与指数涨跌榜",
                "查询最近一个已采集交易日的证券和指数收盘行情，返回代码、名称、品类、前收、开高低收、"
                    + "涨跌幅、成交量和成交额，并按涨跌幅从高到低排序。适用于收盘复盘、领涨领跌观察和异常波动初筛。",
                """
                1. 以 market_quote_daily 的最大 observation_date 确定最新可用交易日。
                2. 返回该交易日全部已采集证券和指数的核心行情字段。
                3. 按涨跌幅降序排列；结合 instrument_type 区分股票、指数、基金、债券等品类。
                4. 结果可能受最大行数截断，不应据此断言覆盖全市场。
                """,
                """
                SELECT observation_date, quote_code, quote_name, instrument_type,
                       previous_close, open, high, low, close, change_pct,
                       volume10_k_units, amount10_k_cny, amount100_m_cny, source_code
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM market_quote_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM market_quote_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY change_pct DESC, quote_code
                """,
                emptySchema(),
                List.of("A股", "指数", "行情", "收盘复盘", "涨跌幅", "领涨领跌", "成交额"),
                "latest_market_movers",
                200,
                "每行代表一个证券或指数在最新可用交易日的行情；change_pct 沿用来源数据口径。"
            ),
            sample(
                "builtin-market-security-history",
                "sample_security_quote_history",
                "单一证券近期行情序列",
                "按证券或指数代码查询热数据层中的近期日行情，返回开高低收、涨跌幅和成交信息。"
                    + "适用于趋势观察、波动复核和事件前后价格表现分析；热明细默认仅保留最近7天。",
                """
                1. 用户输入精确的证券或指数代码 security_code。
                2. 使用命名参数进行预编译查询，避免字符串拼接。
                3. 按业务日期倒序返回近期行情。
                4. 若需要多年历史，应改用周快照或专门历史数据集，不能将热层结果解释为完整历史。
                """,
                """
                SELECT observation_date, quote_code, quote_name, instrument_type,
                       previous_close, open, high, low, close, change_pct,
                       volume10_k_units, amount10_k_cny, amount100_m_cny, source_code
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM market_quote_daily market_rows
                    WHERE quote_code = :security_code
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY observation_date DESC, collected_at DESC
                """,
                schema(Map.of(
                    "security_code", property("string", "证券或指数代码，例如 000001、000001.SH。必须与采集数据中的 quote_code 完全一致。")
                ), List.of("security_code")),
                List.of("证券代码", "历史行情", "趋势", "波动", "成交额"),
                "security_quote_history",
                50,
                "每行代表目标代码在一个交易日的行情；结果范围受热数据保留期和最大行数共同限制。"
            ),
            sample(
                "builtin-market-etf-scale",
                "sample_etf_latest_scale",
                "最新ETF规模与份额观察",
                "查询最近一个已采集日期的ETF规模数据，返回基金代码、规模或总份额以及原始结构化载荷。"
                    + "适用于ETF规模横向比较和资金关注度初筛；规模变化不能单独等同于净申购资金。",
                """
                1. 确定 ETF 数据集最近观测日期。
                2. 返回当日基金代码、标准化规模字段及原始载荷。
                3. 按基金规模降序排列。
                4. 分析时必须核对来源口径，避免把份额变化直接解释为资金净流入。
                """,
                """
                SELECT observation_date, fund_code, fund_scale10_k_units, source_code, payload_json
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM etf_scale_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM etf_scale_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY fund_scale10_k_units DESC, fund_code
                """,
                emptySchema(),
                List.of("ETF", "基金规模", "基金份额", "资金关注度"),
                "etf_scale_analysis",
                100,
                "fund_scale10_k_units 的单位为万份或来源定义的规模单位；详细名称和类别可从 payload_json 核对。"
            ),
            sample(
                "builtin-market-margin-trade",
                "sample_margin_trade_latest",
                "最新融资融券数据观察",
                "查询融资融券数据集最近一个业务日的结构化记录。返回标准审计字段和原始载荷，"
                    + "用于识别融资余额、融资买入、融券卖出及个券杠杆变化。不同交易所字段口径应通过 payload_json 核对。",
                """
                1. 确定融资融券数据集最近观测日期。
                2. 返回最近日期的来源、记录键和完整原始载荷。
                3. 分析模型从 payload_json 中提取融资余额、买入额、融券余量等实际存在字段。
                4. 不跨交易所直接相加口径不一致的指标，不将融资增加机械解释为后市上涨。
                """,
                """
                SELECT observation_date, source_code, record_key, payload_json
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM margin_trade_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM margin_trade_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY record_key
                """,
                emptySchema(),
                List.of("融资融券", "融资余额", "融券余量", "杠杆资金"),
                "margin_trade_analysis",
                200,
                "payload_json 保留来源字段和单位；分析前必须逐项确认交易所、汇总层级和金额单位。"
            ),
            sample(
                "builtin-market-statistics",
                "sample_exchange_market_statistics",
                "最新交易所市场统计概览",
                "查询最近一个业务日的交易所市场统计记录，包括各板块上市公司数、市值、平均市盈率、"
                    + "成交股数和成交金额等来源载荷。适用于A股收盘报告中的市场规模与成交活跃度分析。",
                """
                1. 确定市场统计数据集最近观测日期。
                2. 返回不同交易所、板块或统计分段的完整载荷。
                3. 按来源和记录键区分统计口径，提取总市值、流通市值、平均市盈率和成交额。
                4. 仅在字段单位与板块范围一致时进行汇总或跨日比较。
                """,
                """
                SELECT observation_date, source_code, record_key, payload_json
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM market_statistics_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM market_statistics_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY source_code, record_key
                """,
                emptySchema(),
                List.of("交易所", "市场统计", "总市值", "成交额", "平均市盈率", "收盘复盘"),
                "exchange_market_overview",
                200,
                "payload_json 中保留板块、指标、单位及来源；跨交易所汇总前必须先统一口径。"
            ),
            sample(
                "builtin-market-yield-curve",
                "sample_bond_yield_curve_latest",
                "最新中债国债收益率曲线",
                "查询中债收益率曲线最近业务日的全部期限点，返回期限和到期收益率。"
                    + "适用于观察利率水平、期限结构、长短端变化和期限利差。",
                """
                1. 确定收益率曲线数据集最近业务日。
                2. 按曲线名称和待偿期限升序返回全部期限点。
                3. 可比较短端、中端和长端收益率，判断曲线陡峭化或平坦化。
                4. 单日截面不能证明趋势；趋势判断需要与其他交易日或周快照比较。
                """,
                """
                SELECT observation_date, curve_name, curve_type, maturity_years, yield_pct, source_code
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM bond_yield_curve_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM bond_yield_curve_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY curve_name, maturity_years
                """,
                emptySchema(),
                List.of("中债", "国债收益率", "收益率曲线", "期限结构", "利率债"),
                "bond_yield_curve_analysis",
                100,
                "maturity_years 为待偿期限（年），yield_pct 为到期收益率百分比；不同曲线名称不可直接混为一条曲线。"
            ),
            sample(
                "builtin-market-bond-settlement",
                "sample_bond_settlement_latest",
                "最新中债市场结算统计",
                "查询最近结算日的现券、回购、质押式回购、买断式回购、远期交易等结算规模和笔数，"
                    + "用于判断银行间债券市场活跃度与资金交易结构。",
                """
                1. 确定债券结算数据集最近结算日期。
                2. 返回各结算业务类型的本金、面值、资金金额和笔数。
                3. 区分现券与各类回购，不重复累计包含“合计”的记录。
                4. 结算规模反映市场活动，不等同于净资金流入或方向性交易判断。
                """,
                """
                SELECT observation_date, settlement_time, settlement_type,
                       principal_amount100_m_cny, face_amount100_m_cny,
                       funds_amount100_m_cny, transaction_count, source_code
                FROM (
                    SELECT market_rows.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY observation_date, source_code, source_url
                               ORDER BY collected_at DESC, id DESC
                           ) AS observation_rank
                    FROM bond_settlement_daily market_rows
                    WHERE observation_date = (SELECT MAX(observation_date) FROM bond_settlement_daily)
                ) latest_rows
                WHERE observation_rank = 1
                ORDER BY settlement_type
                """,
                emptySchema(),
                List.of("中债", "债券结算", "现券", "回购", "结算规模", "交易笔数"),
                "bond_settlement_analysis",
                100,
                "金额字段单位为亿元，transaction_count 为结算笔数；若存在合计行，汇总时应避免重复计算。"
            )
        );
    }

    private static Sample sample(String id, String toolName, String title, String description,
                                 String implementationSteps, String sql, Map<String, Object> inputSchema,
                                 List<String> tags, String intent, int maxRows, String resultSemantics) {
        return new Sample(id, toolName, title, description, implementationSteps.strip(), sql.strip(),
            inputSchema, List.copyOf(tags), intent, maxRows, resultSemantics);
    }

    private static Map<String, Object> emptySchema() {
        return schema(Map.of(), List.of());
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "object");
        result.put("properties", properties);
        result.put("required", required);
        result.put("additionalProperties", false);
        return Map.copyOf(result);
    }

    private static Map<String, Object> property(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    public record Sample(
        String id,
        String toolName,
        String title,
        String description,
        String implementationSteps,
        String sql,
        Map<String, Object> inputSchema,
        List<String> tags,
        String intent,
        int maxRows,
        String resultSemantics
    ) {
    }
}
