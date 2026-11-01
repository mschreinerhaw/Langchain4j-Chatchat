# 商品 3 · 1999 年月度销售分析（记录数 / 销售数量 / 净收款 / 利润）

**结论：在本次授权的数据快照中，商品 3 于 1999-01-01 至 1999-12-31、数量下限 0、不限品类的条件下，没有任何销售记录。** 执行模板的成功回执声明 `resultComplete=true`、`sourceVerifiedEmpty=true`、返回行数 `0`，且交付物仅含一个 0 行的 Parquet 分片（已校验 SHA-256）。因此 1999 年不存在可汇总的月度销售数量、净收款与利润观测值；这些指标是**无观测（缺失）**，不是已测得的 0 金额，也不是已测得的 0 数量。

- 全年匹配记录数合计：**0**（经空结果验证的空源，不是缺失）
- 全年销售数量 / 净收款 / 利润合计：**不可计算**（无任何匹配行，无观测值）

## 1. 请求口径与执行口径的比对

执行来源为授权模板 `STUDIO_2B689C67D34336599B05C753F0473B60`（数据集引用 ），
`templateParameterBinding` 显示全部参数来源为 `EXECUTION_RECEIPT`，与原始问题逐项一致：

| 用户约束 | 生效参数 | 实际施加的谓词（取自 queryDefinition） | 一致性 |
|---|---|---|---|
| 商品 3 | product_id = 3 | `ss.ss_item_sk =?`（第 1 个参数标记） | 一致（但见第 5 节限制 3） |
| 起始 1999-01-01 | start_date = 1999-01-01 | `d.d_date >=?`（第 2 个标记） | 一致 |
| 截止 1999-12-31 | end_date = 1999-12-31 | `d.d_date <=?`（第 3 个标记） | 一致 |
| 数量下限 0 | min_quantity = 0 | `ss.ss_quantity >=?`（第 4 个标记） | 一致 |
| 不限品类 | category = null | `(? IS NULL OR i.i_category =?)`（第 5、6 个标记） | 一致（NULL 不过滤） |

`orderedParameterNames` = `["product_id","start_date","end_date","min_quantity","category","category"]`，
共 6 个 JDBC 标记，与 SQL 中 6 个 `?` 逐一对应，未发现参数错位。
时间范围来自 `date_dim.d_date`（经 `ss.ss_sold_date_sk = d.d_date_sk` 关联），不是装载时间戳。

## 2. 数据质量与完整性核验

| 核验项 | 结果 |
|---|---|
| manifest.json SHA-256 | 已校验，与期望值一致（True） |
| schema.json SHA-256 | 已校验，与期望值一致（True） |
| 分片数 | 1（part-00000.parquet，164 字节） |
| 分片 SHA-256 | 已校验一致（True） |
| 分片实际行数 / 声明行数 | 0 / 0 |
| Parquet 列（观测结构） | _empty |
| manifest `columns[]` 逻辑列 | 0 个 |
| 生产者声明 | `deliveryStatus=EMPTY_RESULT`，`resultComplete=true`，`sourceVerifiedEmpty=true` |
| 空值/缺失 | 无行可评估（不存在被丢弃的 null 记录，因为总体为空） |

空源由生产者声明与交付物 SHA-256 共同确认，属“经核验的空结果”，因此第 3 节的记录数 0 是有证据的，
而不是解析失败后的默认填充。

## 3. 月度结果（1999 年）

| 月份 | 销售记录数 | 销售数量 | 净收款 | 利润 |
|---|---:|---:|---:|---:|
| 1999-01 | 0（无匹配行） | — | — | — |
| 1999-02 | 0（无匹配行） | — | — | — |
| 1999-03 | 0（无匹配行） | — | — | — |
| 1999-04 | 0（无匹配行） | — | — | — |
| 1999-05 | 0（无匹配行） | — | — | — |
| 1999-06 | 0（无匹配行） | — | — | — |
| 1999-07 | 0（无匹配行） | — | — | — |
| 1999-08 | 0（无匹配行） | — | — | — |
| 1999-09 | 0（无匹配行） | — | — | — |
| 1999-10 | 0（无匹配行） | — | — | — |
| 1999-11 | 0（无匹配行） | — | — | — |
| 1999-12 | 0（无匹配行） | — | — | — |

说明：销售数量、净收款、利润三列以“—”表示**无观测值**。整个查询返回 0 行，
因此不存在任何月度分组行，也不存在可求和的分组金额；把它们写成 0 会等于把缺失当成已测量的零。

## 4. 字段溯源（数据集 ）

| 业务角色 | 精确源字段 | 生产者声明的含义 | 声明来源 | 状态 |
|---|---|---|---|---|
| 销售年 | `d.d_year`（别名 sale_year） | 分组用年份（1999 年筛选口径经 `d.d_date`） | 执行 SQL queryDefinition | CONFIRMED |
| 销售月 | `d.d_moy`（别名 sale_month） | 分组用月份 | 执行 SQL queryDefinition | CONFIRMED |
| 品类 | `i.i_category`（别名 category） | 商品品类；本次为 NULL 即不过滤 | 执行 SQL queryDefinition + 模板描述 | CONFIRMED |
| 销售记录数 | `COUNT(*)`（别名 row_count） | 分组内销售明细行数（派生量） | 执行 SQL queryDefinition | CONFIRMED |
| 销售数量 | `ss.ss_quantity`（SUM → total_quantity） | 销售数量（派生量为求和） | 执行 SQL queryDefinition + 模板描述“数量” | CONFIRMED |
| 净收款 | `ss.ss_net_paid`（SUM → total_net_paid） | 净收款金额（派生量为求和） | 执行 SQL queryDefinition + 模板描述“净收款” | CONFIRMED |
| 利润 | `ss.ss_net_profit`（SUM → total_net_profit） | 利润金额（派生量为求和） | 执行 SQL queryDefinition + 模板描述“利润” | CONFIRMED |
| 商品筛选键 | `ss.ss_item_sk` | 商品维度代理键，模板将其声明为 product_id 选择字段 | 执行 SQL queryDefinition + templateParameterBinding | CONFIRMED（绑定层面） |
| 业务日期 | `ss.ss_sold_date_sk` → `d.d_date_sk` | 销售日期键关联日期维，用于起止日期过滤 | 执行 SQL queryDefinition | CONFIRMED |

`fieldDefinitions` 为空对象，因此上表含义来自执行 SQL 的 SELECT 表达式/别名与模板描述；
未提供任何独立数据字典。

## 5. 限制与不可判定项

1. **金额与数量单位未声明**：模板与字段定义均未给出净收款、利润的货币单位（也未给出是否含税/折扣后口径之外的说明），
   数量亦未给出计量单位（件/箱等）。若后续有数据，所有金额与数量都必须以“单位未声明”标注。
2. **结果为空，无法区分两类原因**：可能是商品 3 在 1999 年确无销售，也可能是该商品维度中不存在键值 3，
   或授权快照未覆盖该期间。本次交付物不含商品维度表或日期维度覆盖率，无法进一步区分。
3. **商品身份依赖代理键绑定**：`ss.ss_item_sk` 是商品维度代理键（数据仓库内部键）。运行时模板把它绑定为“商品 3”，
   这一绑定是生产者声明；若用户所指“商品 3”是业务商品编码（例如 `i_item_id` 这类编码），
   则该绑定不等价，需要商品主数据/字典确认后才能重跑。
4. **输出上限 24 行**：queryDefinition 带 `LIMIT 24`。品类不限时 12 个月 × 多品类理论上可能超过 24 行而被截断；
   本次结果为空，该上限未产生实际影响，但若未来重跑为非空结果，需先确认未被截断。
5. **无逐笔明细审计**：所有口径只能核对到聚合查询与其参数，交付物没有明细行，无法做记录级复核。
6. 本报告不含趋势图或可视化：唯一的月度结果是“无数据”，没有可绘制的观测值。

*计算方式：读取 manifest → 校验 manifest/schema/分片 SHA-256 → 用 pyarrow 读取 Parquet 实际行数与列结构 →
按生产者声明核对行数 → 在 0 行条件下如实标注为“无观测”而非 0。所有数字直接来自上述校验结果。*