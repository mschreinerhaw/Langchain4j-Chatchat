# 仓库2 逐日库存快照（1998-01-01 ~ 1998-01-29）

## 业务结论

在已交付的执行范围内（`inv_warehouse_sk = 2`、`d_date BETWEEN 1998-01-01 AND 1998-01-29`、`inv_quantity_on_hand >= 0`、类别不作限制），来源返回 **5 个有记录的日期**，逐日记录数合计与现存数量合计如下：

| 指标（来源逻辑字段） | 数值 |
| --- | --- |
| 库存记录数合计（`sku_record_count`，逐日 `COUNT(*)` 之和） | 42,752 |
| 现存数量合计（`total_on_hand`，逐日 `SUM(inv_quantity_on_hand)` 之和） | 21,566,880 |

其中数量大于 0 的记录合计 42,706 条，数量等于 0 的记录合计 46 条；两者相加等于记录数合计（42,752 = 42,752）。

> 现存数量的计量单位未由来源声明，来源说明明确"数量单位未由来源声明，不作推断"，因此上述合计只代表来源数量字段的算术和，不解读为件/箱/货币等任何具体单位。

## 逐日明细

| 日期 `snapshot_date` | 库存记录数 `sku_record_count` | 现存数量合计 `total_on_hand` | 数量>0 记录数 `nonzero_sku_count` | 数量=0 记录数 `zero_sku_count` | 内部加总校验 |
| --- | --- | --- | --- | --- | --- |
| 1998-01-01 | 8,546 | 4,301,064 | 8,539 | 7 | 通过 |
| 1998-01-08 | 8,566 | 4,309,382 | 8,559 | 7 | 通过 |
| 1998-01-15 | 8,542 | 4,309,203 | 8,529 | 13 | 通过 |
| 1998-01-22 | 8,560 | 4,349,344 | 8,550 | 10 | 通过 |
| 1998-01-29 | 8,538 | 4,297,887 | 8,529 | 9 | 通过 |
| **合计（5 天）** | **42,752** | **21,566,880** | **42,706** | **46** | — |

全部 5 天的 `nonzero_sku_count + zero_sku_count = sku_record_count` 均成立。

## 覆盖范围与数据质量

- **日期覆盖不完整**：请求区间共 29 个自然日，来源仅返回 5 天的记录；无记录的日期为：1998-01-02, 1998-01-03, 1998-01-04, 1998-01-05, 1998-01-06, 1998-01-07, 1998-01-09, 1998-01-10, 1998-01-11, 1998-01-12, 1998-01-13, 1998-01-14, 1998-01-16, 1998-01-17, 1998-01-18, 1998-01-19, 1998-01-20, 1998-01-21, 1998-01-23, 1998-01-24, 1998-01-25, 1998-01-26, 1998-01-27, 1998-01-28。
- 查询为每日 `COUNT(*)`/`SUM()` 分组，且结果行数（5）远小于语句 `LIMIT 100`，来源回执 `resultComplete=true`，故可确认这不是结果截断。但"该日无满足条件的库存记录"与"来源系统在该日没有库存历史数据"在已交付数据中无法区分，因此本报告不把缺失日期表述为"库存为零"。
- 日期唯一性：`snapshot_date` 无重复（每日期一行）；无超出请求区间的日期。
- 缺失值：5 个逻辑字段在 5 行中缺失数均为 0；无显式 null。
- 现存数量与记录数无负值异常；逐日内部加总校验全部通过。
- 下限过滤 `inv_quantity_on_hand >= 0` 会在聚合前剔除负数量记录，这是执行范围的既有效果，本报告不做补数。

## 执行范围核验（与原始需求的对照）

- 模板：`STUDIO_FB8D154E3FE039E59A58FDDBBAE1CB1A`，执行方式 PREPARED_STATEMENT（JDBC 参数按顺序绑定）。
- 生效参数：`warehouse_id=2`、`start_date=1998-01-01`、`end_date=1998-01-29`、`min_on_hand=0`、`category=null`，全部来源为 EXECUTION_RECEIPT。
- 参数按 `orderedParameterNames` 顺序对应语句标记：`inv.inv_warehouse_sk =?` ← `warehouse_id`；`dd.d_date >=?` ← `start_date`；`dd.d_date <=?` ← `end_date`；`inv.inv_quantity_on_hand >=?` ← `min_on_hand`；`(? IS NULL OR i.i_category =?)` ← `category`（两次），`null` 使类别条件恒真，即不限制类别。
- 需求要求"数量下限使用默认值"：已记录生效值为 0，但模板默认值声明未随数据交付，无法独立核对 0 是否为模板默认，仅能确认本次执行使用 0。

## 字段来源（field provenance）

| 数据集引用 | 源字段 | 声明含义 | 声明来源 | 状态 |
| --- | --- | --- | --- | --- |
|  | `snapshot_date` | 库存快照的日期（`date_dim.d_date`），语句按此分组并升序 | sourceDefinition.queryDefinition（） | CONFIRMED |
|  | `sku_record_count` | 当日库存记录条数（`COUNT(*)`，为记录数而非去重 SKU 数） | sourceDefinition.queryDefinition（） | CONFIRMED |
|  | `total_on_hand` | 当日 `inv_quantity_on_hand` 求和（现存数量合计，单位未声明） | sourceDefinition.queryDefinition（） | CONFIRMED |
|  | `nonzero_sku_count` | 当日 `inv_quantity_on_hand > 0` 的记录数 | sourceDefinition.queryDefinition（） | CONFIRMED |
|  | `zero_sku_count` | 当日 `inv_quantity_on_hand = 0` 的记录数 | sourceDefinition.queryDefinition（） | CONFIRMED |

派生指标说明：本报告的"库存记录数合计"42,752 为逐日 `sku_record_count` 之和；"现存数量合计"21,566,880 为逐日 `total_on_hand` 之和。两者均为对来源逐日聚合值的再求和，未做任何重算或修订。

## 查询结果明细

已找到 5 行数据，下面展示前 5 行；完整结构化数据已随结果返回用于表格展示。

| snapshot_date | sku_record_count | nonzero_sku_count | zero_sku_count | total_on_hand |
| --- | --- | --- | --- | --- |
| 1998-01-01 | 8546 | 8539 | 7 | 4301064 |
| 1998-01-08 | 8566 | 8559 | 7 | 4309382 |
| 1998-01-15 | 8542 | 8529 | 13 | 4309203 |
| 1998-01-22 | 8560 | 8550 | 10 | 4349344 |
| 1998-01-29 | 8538 | 8529 | 9 | 4297887 |