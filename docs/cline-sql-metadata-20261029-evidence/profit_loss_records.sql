-- 客户号 070200046604 的盈亏原始记录查询
-- 说明：基于发现的历史表生成，仅查询原始字段，未生成推断计算公式

-- 1. 证券投资损益记录 (TTZSY - 历史表)
-- 字段说明：
--   KHH: 客户号
--   ZQDM: 证券代码
--   ZQMC: 证券名称
--   KCRQ: 开仓日期
--   JSRQ: 清仓日期
--   CCSL: 持仓数量
--   MRJE: 买入金额
--   MCJE: 卖出金额
--   JYFY: 交易费用
--   BZ: 币种
SELECT 
    SEQNO,
    LSH,
    KHH,
    KHXM,
    JYS,
    GDH,
    ZQDM,
    ZQMC,
    KCRQ,
    JSRQ,
    CCSL,
    MRJE,
    MCJE,
    JYFY,
    BZ,
    YYB,
    KHQZ,
    GSFL
FROM DATACENTER.TTZSY
WHERE KHH = '070200046604'
ORDER BY JSRQ DESC, LSH DESC;

-- 2. 个股期权投资损益记录 (TSO_TZSY - 历史流水表)
-- 字段说明：
--   KHH: 客户号
--   HYDM: 期权合约代码
--   HYMC: 期权合约简称
--   KCRQ: 开仓日期
--   JSRQ: 清仓日期
--   CCSL: 持仓数量
--   KCJE: 开仓金额
--   PCJE: 平仓金额
--   MMFX: 买卖方向
--   BZ: 币种
SELECT 
    SEQNO,
    LSH,
    KHH,
    KHXM,
    YYB,
    BZ,
    KHQZ,
    JYS,
    GDH,
    ZZHBM,
    KCRQ,
    JSRQ,
    HYDM,
    HYMC,
    QQLX,
    ZQLX,
    ZQDM,
    ZQMC,
    MMFX,
    BDBQ,
    CCSL,
    KCJE,
    PCJE
FROM DATACENTER.TSO_TZSY
WHERE KHH = '070200046604'
ORDER BY JSRQ DESC, LSH DESC;
