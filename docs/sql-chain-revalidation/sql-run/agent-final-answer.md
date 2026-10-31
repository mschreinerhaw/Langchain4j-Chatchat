# tpcds 门店销售对账：商品主键 1 · 1999 年度汇总 vs 逐月明细

## 1. 结论摘要

在本次实际返回的两个结果集之间，1999 年度的**年度汇总**与**逐月明细加总**存在差额，按项列示如下（年度 − 逐月）：

- 销售行数（sales_rows / row_count）：年度 66，逐月加总 65，差额 **1**（占年度 1.52%）。
- 净收款合计（total_net_paid）：年度 136,947.06，逐月加总 136,714.55，差额 **232.51**（占年度 0.17%）。
- 利润合计（total_net_profit）：年度 -65,502.46，逐月加总 -63,358.97，差额 **-2,143.49**（占年度 3.27%）。

差额的成因**未能在已交付证据内分解**：两个结果集都是聚合输出，不含明细行，且逐月查询的实际生效参数（起止日期、最低购买数量、品类）未随数据集声明，因此本报告只确认差额的存在与量级，不把它判为数据错误，也不归因到某一业务原因。

覆盖情况：逐月结果实际包含 1999 年的 11 个月（1、2、4、5、6、7、8、9、10、11、12 月），**缺少 3 月**；缺少月份可能代表该月无销售行，也可能是查询条件或返回不完整，已交付证据不足以区分，未按 0 补齐。

金额单位：两个结果集的生产者声明均未给出金额单位，本报告**不推断货币单位**，金额以原始数值列示。

## 2. 数据来源与作用域

| 数据集引用 | 生产声明用途 | 返回行数 | 本次使用范围 |
| --- | --- | --- | --- |
|  | 限定商品 ss_item_sk=1，按实际销售日期年度汇总销售行数、数量、净收款和利润，排除无日期维度关联记录；无参数 | 6 | 仅取 sales_year=1999 的行 |
|  | 指定商品、起止日期、最低购买数量及可选品类的门店逐月销售、数量、净收款及利润；默认商品 1，NULL 品类不过滤 | 11 | 取返回结果中 sale_year=1999 的行 |
| `internal_reconciliation#payload` | 分块交付的受治理结果载荷（非业务行；recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | 1 块 | 仅用于重建方法说明，不含业务数值 |

作用域核对（重要）：

- 年度汇总模板声明为**无参数**，商品在主键层面固定为 ss_item_sk=1，覆盖全部年度；实际返回 6 个年度（1998、1999、2000、2001、2002、2003），本次仅使用 1999 行，其余年度只作上下文。
- 逐月模板有起止日期、最低购买数量、品类三类参数，但本次运行未提供生效参数绑定（templateParameterBinding 为空），因此**无法用配置证明**它实际绑定为 1999-01-01~1999-12-31、月度最低购买数量 0、品类不限；只能依据返回行实际的 sale_year/sale_month 取值判断覆盖区间。
- 逐月结果为月粒度，无法验证年内的日边界（例如是否 1 月 1 日起、12 月 31 日止）。
- 两个结果集都不含商品主键列；商品 ss_item_sk=1 的身份仅由生产者声明保证。

## 3. 年度汇总结果（模板 `212b8832…`，全部年度，1999 在范围内）

| sales_year（年度） | sales_rows（销售行数） | total_quantity（数量） | total_net_paid（净收款） | total_net_profit（利润） |
| --- | --- | --- | --- | --- |
| 1998 | 69 | 3,271 | 95,321.87 | -56,881.40 |
| 1999 **（对账口径）** | 66 | 3,362 | 136,947.06 | -65,502.46 |
| 2000 | 64 | 3,276 | 133,897.01 | -39,360.37 |
| 2001 | 48 | 2,076 | 66,291.00 | -42,350.41 |
| 2002 | 60 | 3,115 | 138,561.11 | -48,800.13 |
| 2003 | 2 | 157 | 2,373.12 | -7,540.34 |

## 4. 逐月明细（1999 年，模板 `b1e518f3…` 实际返回行）

| sale_year | sale_month | category | row_count（行数） | total_quantity（数量） | total_net_paid（净收款） | total_net_profit（利润） |
| --- | --- | --- | --- | --- | --- | --- |
| 1999 | 1 | Music | 3 | 217 | 21,884.51 | 7,416.63 |
| 1999 | 2 | Music | 3 | 170 | 2,138.52 | -2,296.74 |
| 1999 | 4 | Music | 2 | 151 | 8,459.79 | -3,151.90 |
| 1999 | 5 | Music | 1 | 64 | 2,899.84 | 636.80 |
| 1999 | 6 | Music | 2 | 122 | 6,473.64 | -1,004.15 |
| 1999 | 7 | Music | 3 | 177 | 6,585.36 | -4,990.27 |
| 1999 | 8 | Music | 7 | 336 | 13,543.25 | -11,742.70 |
| 1999 | 9 | Music | 8 | 349 | 19,070.61 | 783.00 |
| 1999 | 10 | Music | 10 | 460 | 15,368.53 | -12,015.39 |
| 1999 | 11 | Music | 11 | 575 | 17,273.35 | -20,266.71 |
| 1999 | 12 | Music | 15 | 741 | 23,017.15 | -16,727.54 |

逐月合计（按 1999 年已返回月份加总）：行数 65，数量 3,362，净收款 136,714.55，利润 -63,358.97。

## 5. 勾稽对账（1999 年；年度汇总 − 逐月加总）

| 指标（来源列） | 年度汇总 1999 | 逐月加总 1999 | 差额（年度−逐月） | 差额占年度 |
| --- | --- | --- | --- | --- |
| 销售行数（sales_rows / row_count） | 66 | 65 | 1 | 1.52% |
| 数量合计（total_quantity） | 3,362 | 3,362 | 0 | 0.00% |
| 净收款合计（total_net_paid） | 136,947.06 | 136,714.55 | 232.51 | 0.17% |
| 利润合计（total_net_profit） | -65,502.46 | -63,358.97 | -2,143.49 | 3.27% |

对账口径说明：行数项比较年度 `sales_rows` 与逐月 `row_count` 之和；数量项比较 `total_quantity` 之和；金额项用 Decimal 从返回数值精确相加，保留两位小数比较，差额为 0 表示在该精度下完全相等。

## 6. 差额解释与证据边界

差额已量化，但以下可比子总计在已交付数据中**无法检验**，因此差额不能被判为数据错误，也不能归因到具体原因：

1. 年度汇总声明“排除无日期维度关联记录”，逐月模板未声明其对日期维度的关联/排除规则——若两者过滤不同，任何一项都可能产生差额。
2. 逐月模板的起止日期、最低购买数量实际生效值未随数据集交付；若最低购买数量不为 0，整月行可能被过滤掉，从年对账上表现为年度大于逐月。
3. 逐月结果缺少 3 月的行；无法区分“该月无销售”与“该月被条件过滤或返回不完整”，因此逐月加总不是年度总额的完备分解。
4. 金额单位未声明，任何按单位换算或跨源合并（如与其他货币/量表汇总）都不成立，本报告不做。
5. 缺少明细行（两个数据集均为聚合输出），差额无法按商品、门店、渠道等维度进一步拆分。

判定原则：只有当两侧数值的指标、范围、期间、口径、单位都确认一致时，差额才可判为数据错误。本次范围与参数口径未获确认，因此差额保留为**未决勾稽成分**。

## 7. 数据质量与结构核查

| 检查项 | 结果 |
| --- | --- |
| manifest SHA-256 | 与任务声明一致（8b55d9fd0a2dd576…） |
| schema SHA-256 | 与任务声明一致（d81274014bb3d60d…） |
| 分片文件数与行数 | 1 个分片，合计 18 行，与 manifest rowCount=18 一致 |
| 逻辑行分组 | 年度汇总 6 行、逐月明细 11 行、载荷 1 块 |
| 分块载荷完整性 | chunkIndex 连续、fromChar/toChar 覆盖 0–122 无缺口，sourceComplete=true |
| 年度汇总销售年度唯一性 | 重复年度：无 |
| 逐月重复键 (sale_year,sale_month,category) | 无 |
| 逐月覆盖年度 | [1999]（品类取值：Music） |
| 缺失月份 | 3 月 |
| 空值/缺失字段 | 未观察到 |

## 8. 限制

1. 逐月模板的生效参数未交付（templateParameterBinding 为空），1999-01-01~1999-12-31、月度最低购买数量 0、品类不限这三项只能依据返回行的 sale_year/sale_month 观测推断覆盖情况，不能证明查询配置与用户要求等价。
2. 年度汇总模板声明为无参数，商品固定为 ss_item_sk=1；两个结果集都不含商品主键列，商品身份仅由模板声明保证，无法在数据中复核。
3. 逐月结果为月粒度，无法验证年内的日边界；逐月缺少的月份既可能是真实无销售，也可能是过滤或返回不完整，未按 0 补齐。
4. 逐月明细缺少 3 月，年度与逐月的差额因此不是完备分解，差额成因不可分解。
5. 两个数据集均为聚合结果，无明细行；无法进一步拆分差额，也无法检验年度模板“排除无日期维度关联记录”与逐月模板的关联规则是否一致。
6. 净收款与利润的金额单位未由数据来源声明，本报告不推断货币单位，也不做任何单位换算或跨源合并。
7. 年度汇总结果另含 earliest_date / latest_date 两列，生产者未给出列级定义，本报告未使用这两列作时间范围判断（不按名称推断含义）。
8. 内部载荷（internal_reconciliation#payload）仅包含对账方法说明文本，不含业务数值，不能作为独立数据来源验证两侧结果。
9. 对账在返回数值的两位小数精度下比较，不做四舍五入政策之外的调整。

## 9. 字段溯源

| 数据集 | 源字段 | 声明含义 | 声明来源 | 状态 |
| --- | --- | --- | --- | --- |
|  | `sales_year` | 销售发生的年度（声明按其“实际销售日期”年度汇总） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `sales_rows` | 该年度的销售行数 | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_quantity` | 该年度数量合计（数量单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_net_paid` | 该年度净收款合计（金额单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_net_profit` | 该年度利润合计（金额单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `sale_year` | 销售发生的年度（逐月明细） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `sale_month` | 销售发生的月份（逐月明细，月粒度） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `category` | 品类维度取值（模板声明“可选品类”，NULL 表示不过滤） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `row_count` | 该月销售行数 | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_quantity` | 该月数量合计（数量单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_net_paid` | 该月净收款合计（金额单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
|  | `total_net_profit` | 该月利润合计（金额单位未声明） | inputVersions.sourceDefinition.description（模板 ） | CONFIRMED |
| `internal_reconciliation#payload` | `content` | 分块载荷内容（对账方法说明文本，非业务记录） | inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | CONFIRMED |
| `internal_reconciliation#payload` | `chunkIndex` | 分块序号（用于按声明顺序重建） | inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | CONFIRMED |
| `internal_reconciliation#payload` | `fromChar` | 分块起始字符偏移（覆盖性校验） | inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | CONFIRMED |
| `internal_reconciliation#payload` | `toChar` | 分块结束字符偏移（覆盖性校验） | inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | CONFIRMED |
| `internal_reconciliation#payload` | `sourceComplete` | 来源完整性标志 | inputVersions 表示声明 runtime_os.coding.source-representation.v1（recordMeaning=CONTENT_CHUNK_NOT_BUSINESS_ROW） | CONFIRMED |

派生指标（本报告计算，非源字段）：逐月加总 = Σ 各月 `row_count`/`total_quantity`/`total_net_paid`/`total_net_profit`；差额 = 年度 `sales_rows`/`total_quantity`/`total_net_paid`/`total_net_profit` − 对应逐月加总；差额占年度 = 差额 ÷ 年度值（年度值为 0 时不计算）。

分析状态：**PARTIAL** — 1999 年度的年度/逐月勾稽已按实际返回结果完成并量化差额；但逐月查询的生效参数、年内日边界、缺失月份语义、金额单位未获确认，不能宣称查询范围与用户要求完全等价。

## 查询结果明细

已找到 6 行数据，下面展示前 6 行；完整结构化数据已随结果返回用于表格展示。

| sales_year | sales_rows | total_quantity | total_net_paid | total_net_profit | earliest_date | latest_date |
| --- | --- | --- | --- | --- | --- | --- |
| 1998 | 69 | 3271 | 95321.87 | -56881.4 | 1998-01-11 | 1998-12-28 |
| 1999 | 66 | 3362 | 136947.06 | -65502.46 | 1999-01-12 | 1999-12-31 |
| 2000 | 64 | 3276 | 133897.01 | -39360.37 | 2000-01-02 | 2000-12-30 |
| 2001 | 48 | 2076 | 66291.0 | -42350.41 | 2001-01-24 | 2001-12-29 |
| 2002 | 60 | 3115 | 138561.11 | -48800.13 | 2002-01-01 | 2002-12-29 |
| 2003 | 2 | 157 | 2373.12 | -7540.34 | 2003-01-02 | 2003-01-02 |