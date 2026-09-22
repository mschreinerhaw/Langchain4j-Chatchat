-- MCP business classifications for securities-company data and business capabilities.
-- These rows are deployment data and can be replaced by customer-specific classifications.
insert into mcp_business_category (enabled, sort_order, created_at, updated_at, id, code, domain, name, description, keywords_json) values
    (true, 10000, current_timestamp, current_timestamp, 'mcp-cat-default', 'default', 'securities', '默认分类', '未指定业务分类时使用的技术兜底分类。', '["default","默认","未分类"]'),
    (true, 100, current_timestamp, current_timestamp, 'mcp-cat-securities-market', 'securities_market', 'securities', '证券市场', '交易所行情、指数、成交、资金和市场微观结构数据。', '["行情","指数","成交","资金","交易所"]'),
    (true, 200, current_timestamp, current_timestamp, 'mcp-cat-macro-strategy', 'macro_strategy', 'research', '宏观策略', '宏观经济、政策、利率、汇率及资产价格传导。', '["宏观","政策","利率","汇率"]'),
    (true, 300, current_timestamp, current_timestamp, 'mcp-cat-industry-research', 'industry_research', 'research', '行业研究', '行业周期、产业链、供需、竞争格局和行业估值。', '["行业","产业链","供需","景气度"]'),
    (true, 400, current_timestamp, current_timestamp, 'mcp-cat-company-research', 'company_research', 'research', '公司研究', '上市公司公告、基本面、公司治理和经营数据。', '["上市公司","基本面","公告","治理"]'),
    (true, 500, current_timestamp, current_timestamp, 'mcp-cat-financial-analysis', 'financial_analysis', 'research', '财务分析', '财务报表、财务指标、盈利质量和现金流数据。', '["财务报表","利润","现金流","财务指标"]'),
    (true, 600, current_timestamp, current_timestamp, 'mcp-cat-valuation-pricing', 'valuation_pricing', 'research', '估值定价', '证券估值模型、可比公司、预测参数和敏感性分析。', '["估值","定价","DCF","可比公司"]'),
    (true, 700, current_timestamp, current_timestamp, 'mcp-cat-asset-allocation', 'asset_allocation', 'investment', '资产配置', '大类资产、风险预算、投资约束和再平衡数据。', '["资产配置","风险预算","再平衡"]'),
    (true, 800, current_timestamp, current_timestamp, 'mcp-cat-portfolio-management', 'portfolio_management', 'investment', '投资组合', '持仓、收益、基准、绩效归因和组合风险数据。', '["组合","持仓","归因","基准"]'),
    (true, 900, current_timestamp, current_timestamp, 'mcp-cat-market-risk', 'market_risk', 'risk', '市场风险', '风险因子、限额、风险价值、压力测试和流动性风险。', '["市场风险","VaR","压力测试","限额"]'),
    (true, 1000, current_timestamp, current_timestamp, 'mcp-cat-compliance', 'compliance', 'risk', '合规风控', '适当性、异常交易、信息隔离、反洗钱和合规检查。', '["合规","适当性","异常交易","反洗钱"]'),
    (true, 1100, current_timestamp, current_timestamp, 'mcp-cat-investment-banking', 'investment_banking', 'business', '投行业务', '股权融资、债券融资、并购重组和项目尽调数据。', '["投行","IPO","债券","并购","尽调"]'),
    (true, 1200, current_timestamp, current_timestamp, 'mcp-cat-wealth-management', 'wealth_management', 'business', '财富管理', '客户画像、风险测评、产品适配和投后服务数据。', '["财富管理","客户画像","产品适配"]'),
    (true, 1300, current_timestamp, current_timestamp, 'mcp-cat-brokerage', 'brokerage', 'business', '经纪业务', '经纪客户、交易行为、渠道、分支机构和服务运营数据。', '["经纪","客户","渠道","分支机构"]'),
    (true, 1400, current_timestamp, current_timestamp, 'mcp-cat-institutional', 'institutional', 'business', '机构业务', '机构客户、研究服务、交易服务和综合金融协同数据。', '["机构客户","研究服务","交易服务"]'),
    (true, 1500, current_timestamp, current_timestamp, 'mcp-cat-fixed-income', 'fixed_income', 'investment', '固定收益', '利率债、信用债、收益率曲线、久期和信用利差。', '["债券","固定收益","收益率曲线","久期"]'),
    (true, 1600, current_timestamp, current_timestamp, 'mcp-cat-derivatives', 'derivatives', 'investment', '衍生品', '期货、期权、场外衍生品、套期保值和保证金数据。', '["期货","期权","衍生品","套期保值"]'),
    (true, 1700, current_timestamp, current_timestamp, 'mcp-cat-quant-research', 'quant_research', 'research', '量化研究', '因子、行情序列、回测、交易成本和模型评估数据。', '["量化","因子","回测","模型"]'),
    (true, 1800, current_timestamp, current_timestamp, 'mcp-cat-disclosure', 'disclosure', 'governance', '信息披露', '上市公司公告、定期报告、监管问询和重大事项。', '["信息披露","公告","定期报告","监管问询"]'),
    (true, 1900, current_timestamp, current_timestamp, 'mcp-cat-credit-research', 'credit_research', 'risk', '信用研究', '发行人、评级、偿债、担保、债券条款和信用事件。', '["信用","发行人","评级","偿债"]'),
    (true, 2000, current_timestamp, current_timestamp, 'mcp-cat-securities-operations', 'securities_operations', 'operations', '运营管理', '账户、资金、清算交收、估值核算和操作风险数据。', '["运营","清算","交收","账户","操作风险"]');
