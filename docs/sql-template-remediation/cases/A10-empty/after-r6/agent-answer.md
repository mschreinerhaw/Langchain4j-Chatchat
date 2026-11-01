# 仓库 99999 逐日库存快照（1998-01-01 至 1998-01-29）

## 结论：该范围内没有任何库存快照记录

本次授权数据集对「仓库 99999、日期 1998-01-01 至 1998-01-29（含首尾）、数量下限 0、类别不限」返回的记录数为 **0**。交付的 Parquet 传输分片经 SHA-256 校验并成功解析，实测 0 行（传输层仅有一个占位列 `_empty`，无任何业务字段），因此**不存在任何逐日库存快照行**可供展示。按你的要求，此处不给虚构、插值或补全的每日数据。

| 项目 | 结果 |
| --- | --- |
| 快照日期行数 | 0（无任何日期行） |
| 记录条数（COUNT 口径） | 0 |
| 数量合计 total_on_hand | 未产生（无观测值，不以 0 代替缺失） |
| 日期序列连续性 / 首末余额 | 无法检查（无观测日期） |

## 查询范围核验（执行参数 vs 你的要求）

| 参数（模板声明名） | 执行生效值 | 你的要求 | 是否一致 | 绑定的 SQL 谓词 |
| --- | --- | --- | --- | --- |
| warehouse_id | 99999 | 仓库 99999 | 一致 | `inv.inv_warehouse_sk =?` |
| start_date | 1998-01-01 | 1998-01-01 | 一致 | `dd.d_date >=?` |
| end_date | 1998-01-29 | 1998-01-29 | 一致 | `dd.d_date <=?` |
| min_on_hand | 0 | 数量下限 0 | 一致 | `inv.inv_quantity_on_hand >=?` |
| category（重复两次参数标记） | null | 类别不限 | 一致 | `(? IS NULL OR i.i_category =?)`，均为 null 即不施加类别过滤 |

参数标记顺序经核对为 6 个 JDBC 占位符，与实际执行 SQL 中的 6 个 `?` 一一对应（顺序：warehouse_id, start_date, end_date, min_on_hand, category, category），因此上述谓词绑定是可验证的执行范围，而不是默认范围。

## 已执行的取数定义（来源声明，用于确认指标公式）

来源模板：TPCDS 248 仓库期间库存快照（按仓库 + 含边界日期范围返回逐日库存聚合）。实际执行的 SELECT 表达式如下，本次未产生任何行：

| 声明输出列 | SELECT 表达式 | 业务含义 |
| --- | --- | --- |
| snapshot_date | `dd.d_date` | 快照日期（date_dim 业务日期） |
| sku_record_count | `COUNT(*)` | 当日库存记录条数 |
| nonzero_sku_count | `SUM(CASE WHEN inv.inv_quantity_on_hand > 0 THEN 1 ELSE 0 END)` | 当日数量 > 0 的记录条数 |
| zero_sku_count | `SUM(CASE WHEN inv.inv_quantity_on_hand = 0 THEN 1 ELSE 0 END)` | 当日数量 = 0 的记录条数 |
| total_on_hand | `SUM(inv.inv_quantity_on_hand)` | 当日现有数量合计（单位未声明） |

## 为什么这是“确认的空结果”，而不是解析失败或数据缺失

| 核验项 | 实测/声明 | 结论 |
| --- | --- | --- |
| manifest.json SHA-256 | `dcdaeede756f9be2a8e72c560172ed11c98c92e65a1dbca5863cfc81a53e60cf` | 与 Runtime 提供的期望值一致 |
| schema.json SHA-256 | `26fa1742b708cd9a47e8282545685db35fddf9e4761e70478ef4ea0a634e0e1d` | 与 Runtime 提供的期望值一致 |
| 传输分片 part-00000.parquet SHA-256 | 校验通过 | 分片 164 字节 |
| 该分片实际行数 | manifest 声明 0；Parquet 元数据 0；实际读取 0 | 三者一致 |
| 该分片列数 | Arrow 模式 1 列（['_empty']）；读入表列数 1 | 空表，无逻辑列可映射 |
| manifest 逻辑列 / 行数 | 0 列 / 0 行 | 与分片行数一致；分片唯一列 `_empty` 是空结果占位列，不是业务字段 |
| nativeFiles（原始字节文件） | 0 个 | 无原始文件可替代读取 |
| 来源完整性回执 | returnedRowCount = 0，resultComplete = true，sourceVerifiedEmpty = true，deliveryStatus = EMPTY_RESULT | 来源声明为“已验证的空结果”，不是失败解析 |

## 空结果的可能原因（均未被本次交付数据证实，不能据此下断言）

1. 仓库键 99999 在 inventory 表中没有任何库存记录（例如该仓库键不存在于库存事实表）；
2. 仓库存在，但 1998-01-01 至 1998-01-29 之间没有可与 inventory.inv_date_sk 匹配的快照日期行（库存快照并非必然每日产生）；
3. 该窗口内的库存数量行全部为负值或 NULL，被 `inv.inv_quantity_on_hand >= 0` 下限谓词排除（该谓词是“数量下限”过滤，不是“仅取非零”过滤）。

本次交付只有该查询的结果集，没有 inventory、date_dim、item 的任何明细，因此无法区分以上三种情形，也无法给出“仓库存在但当日无库存”这类更强结论。

## 字段来源（field provenance）

| 数据集（datasetReference） | 源字段 | 声明含义 | 声明来源 | 状态 |
| --- | --- | --- | --- | --- |
|  | `snapshot_date` | 库存快照日期，取 dd.d_date（date_dim 业务日期列），由 inv.inv_date_sk = dd.d_date_sk 关联 |  queryDefinition (executed SELECT expressions/aliases) | CONFIRMED |
|  | `sku_record_count` | 当日参与统计的库存记录条数，即 COUNT(*)（inventory × date_dim × item 关联后的记录数） |  queryDefinition (executed SELECT expressions/aliases) | CONFIRMED |
|  | `nonzero_sku_count` | 当日 inv.inv_quantity_on_hand > 0 的记录条数（SUM(CASE... THEN 1 ELSE 0 END)） |  queryDefinition (executed SELECT expressions/aliases) | CONFIRMED |
|  | `zero_sku_count` | 当日 inv.inv_quantity_on_hand = 0 的记录条数（SUM(CASE... THEN 1 ELSE 0 END)） |  queryDefinition (executed SELECT expressions/aliases) | CONFIRMED |
|  | `total_on_hand` | 当日 inv.inv_quantity_on_hand 的算术合计 SUM(inv.inv_quantity_on_hand)；数量单位未由来源声明 |  queryDefinition (executed SELECT expressions/aliases) | CONFIRMED |

## 限制与未决项

- 结果集为空，Session 中不存在任何可解读的取值，因此本报告不包含逐日明细表，也没有生成任何库存趋势图（无观测值可绘制）。
- 数量字段的总计、平均值等统计量一律未给出：缺失不等于 0，空结果也不能被呈现为“库存为 0”。
- 数量单位未由来源声明（模板说明明确表示“数量单位未由来源声明，不作推断”），任何按“件/箱/金额”的解读都不成立。
- 参数 warehouse_id=99999 绑定到 `inventory.inv_warehouse_sk`；来源未声明 99999 是仓库业务编码还是代理键，该身份等价性依赖模板参数声明，未由独立字典确认。不过无论该映射如何，本次执行的范围内返回记录数均为 0。
- 执行 SQL 含 `LIMIT 100`；本次 0 行不受影响，但若未来查询跨度超过 100 个快照日期，结果可能被截断，需重新评估。
- 未做跨源核对（inventory / item / date_dim 明细未交付），因此“仓库 99999 是否存在”“该期间是否有快照日期”等问题均未验证。
- 交付数据本身不含任何业务字段（fieldSchema / fieldDefinitions 为空），因此不存在可校验的取值、日期跨度、重复键或数值精度；逐日明细、合计、日期连续性、首末余额与可视化均不适用，字段含义仅来自模板执行 SQL 的声明。